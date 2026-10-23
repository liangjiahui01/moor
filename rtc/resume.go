package main

// Resumable streams: SSH survives a change of path.
//
// A data channel dies with its path (Wi-Fi to mobile data, a frozen app, a network that drops for a
// moment). The machine keeps its sshd connection for a while and the phone dials a new path and
// resumes: each side says how many bytes it has received and the other sends again from there. SSH
// on both ends only sees a pause, so the terminal is not reconnected, replayed or redrawn.
//
// A machine offers this with the channel label "ssh+r1". Every message on such a channel starts with
// a type byte: h (hello, phone → machine, JSON), w (welcome, machine → phone, JSON), d (data), a (ack:
// 8-byte count of bytes received) and b (bye: the sender's input ended). A phone that doesn't know
// this sends SSH right away, which starts with "SSH-", and is served the old way.

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"time"
)

const resumeLabel = "ssh+r1"

const (
	fHello   = 'h'
	fWelcome = 'w'
	fData    = 'd'
	fAck     = 'a'
	fBye     = 'b'
)

const (
	unackedMax = 4 << 20   // stop reading input while this much output is unacknowledged
	ackEvery   = 64 << 10  // acknowledge after this much input
	dataMax    = chunk - 1 // data per message, after the type byte
)

type helloMsg struct {
	SID    string `json:"sid"`
	Resume bool   `json:"resume,omitempty"`
	Recv   uint64 `json:"recv"` // bytes of the machine's output the phone has
}

type welcomeMsg struct {
	Recv uint64 `json:"recv"` // bytes of the phone's output the machine has
	Gone bool   `json:"gone,omitempty"`
}

// msgConn is a data channel seen as messages.
type msgConn interface {
	ReadMsg(p []byte) (int, error)
	WriteMsg(p []byte) error
	Close() error
}

var (
	errResume  = errors.New("the other side lost data it had acknowledged")
	errStopped = errors.New("stopped")
)

// stream is one side of a resumable connection: our input goes out (kept until acknowledged), the
// peer's data comes in and goes to out.
type stream struct {
	mu       sync.Mutex
	cond     *sync.Cond
	buf      []byte // unacknowledged output; buf[0] is byte number base
	base     uint64
	inDone   bool   // our input ended: everything is in buf
	byeSent  bool   // on the current channel
	gen      uint64 // which serve call is current
	recv     uint64 // bytes received and written to out
	out      io.Writer
	closeOut func()
	ended    chan struct{}
	endOnce  sync.Once
}

func newStream(out io.Writer, closeOut func()) *stream {
	s := &stream{out: out, closeOut: closeOut, ended: make(chan struct{})}
	s.cond = sync.NewCond(&s.mu)
	return s
}

func (s *stream) isEnded() bool {
	select {
	case <-s.ended:
		return true
	default:
		return false
	}
}

// end finishes the stream for good (both directions).
func (s *stream) end() {
	s.endOnce.Do(func() {
		close(s.ended)
		s.mu.Lock()
		s.cond.Broadcast()
		s.mu.Unlock()
	})
}

// received is how much of the peer's output we have.
func (s *stream) received() uint64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.recv
}

// pump reads our input into the buffer until it ends, pausing while too much is unacknowledged.
func (s *stream) pump(in io.Reader) {
	tmp := make([]byte, 32<<10)
	for {
		s.mu.Lock()
		for len(s.buf) >= unackedMax && !s.isEnded() {
			s.cond.Wait()
		}
		s.mu.Unlock()
		if s.isEnded() {
			return
		}
		n, err := in.Read(tmp)
		s.mu.Lock()
		s.buf = append(s.buf, tmp[:n]...)
		if err != nil {
			s.inDone = true
		}
		s.cond.Broadcast()
		s.mu.Unlock()
		if err != nil {
			return
		}
	}
}

// ackTo drops output the peer has; s.mu is held. False when the peer claims more than was sent.
func (s *stream) ackTo(n uint64) bool {
	if n < s.base {
		return true // an old ack
	}
	if n > s.base+uint64(len(s.buf)) {
		return false
	}
	s.buf = s.buf[n-s.base:]
	s.base = n
	s.cond.Broadcast()
	return true
}

// doneSending: all our output is acknowledged and the bye went out, so the peer has everything.
func (s *stream) doneSending() bool { return s.inDone && s.byeSent && len(s.buf) == 0 }

// serve carries the stream over ch, starting from peerRecv (how much of our output the peer has),
// until the path dies (its error), abort is closed, or the stream ends (errEnded). It may be called
// again with a new channel after a path died. It doesn't wait for the old channel to close (that can
// hang on a dead path): once it returns, nothing from the old channel reaches out or moves recv.
func (s *stream) serve(ch msgConn, peerRecv uint64, abort <-chan struct{}) error {
	s.mu.Lock()
	if s.isEnded() {
		s.mu.Unlock()
		return errEnded
	}
	if peerRecv < s.base || !s.ackTo(peerRecv) {
		s.mu.Unlock()
		return errResume
	}
	s.gen++
	gen := s.gen
	s.byeSent = false // a bye on a dead path may not have arrived: say it again on this one
	s.mu.Unlock()

	stop := make(chan struct{})
	var wmu sync.Mutex
	write := func(p []byte) error {
		wmu.Lock()
		defer wmu.Unlock()
		return ch.WriteMsg(p)
	}
	var rmu sync.Mutex // held while received data is handed on
	errc := make(chan error, 2)
	go func() { errc <- s.send(stop, gen, write, peerRecv) }()
	go func() { errc <- s.receive(stop, &rmu, ch, write) }()
	var err error
	select {
	case err = <-errc:
	case <-abort:
		err = errors.New("new path asked")
	case <-s.ended:
		err = errEnded
	}
	close(stop)
	s.mu.Lock()
	s.cond.Broadcast()
	s.mu.Unlock()
	go ch.Close()
	// After this, the old receiver sees stop before handing anything on
	rmu.Lock()
	rmu.Unlock()
	if s.isEnded() {
		return errEnded
	}
	return err
}

func stopped(stop chan struct{}) bool {
	select {
	case <-stop:
		return true
	default:
		return false
	}
}

func (s *stream) send(stop chan struct{}, gen uint64, write func([]byte) error, pos uint64) error {
	msg := make([]byte, 1+dataMax)
	byeSent := false
	for {
		s.mu.Lock()
		for !stopped(stop) && !s.isEnded() && pos == s.base+uint64(len(s.buf)) && !(s.inDone && !byeSent) {
			s.cond.Wait()
		}
		if stopped(stop) || s.isEnded() {
			s.mu.Unlock()
			return errStopped
		}
		if pos < s.base {
			pos = s.base
		}
		end := s.base + uint64(len(s.buf))
		if pos < end {
			n := min(end-pos, uint64(dataMax))
			off := pos - s.base
			msg[0] = fData
			copy(msg[1:], s.buf[off:off+n])
			s.mu.Unlock()
			if err := write(msg[:1+n]); err != nil {
				return err
			}
			pos += n
			continue
		}
		// Everything sent and our input ended: say so
		s.mu.Unlock()
		if err := write([]byte{fBye}); err != nil {
			return err
		}
		byeSent = true
		s.mu.Lock()
		if s.gen != gen { // a newer channel took over meanwhile
			s.mu.Unlock()
			return errStopped
		}
		s.byeSent = true
		done := s.doneSending()
		s.mu.Unlock()
		if done {
			s.end()
			return errEnded
		}
	}
}

func (s *stream) receive(stop chan struct{}, rmu *sync.Mutex, ch msgConn, write func([]byte) error) error {
	buf := make([]byte, 64<<10)
	var unacked int
	ack := func() error {
		m := make([]byte, 9)
		m[0] = fAck
		binary.BigEndian.PutUint64(m[1:], s.received())
		unacked = 0
		return write(m)
	}
	for {
		n, err := ch.ReadMsg(buf)
		if err != nil {
			return err
		}
		if n == 0 {
			continue
		}
		switch buf[0] {
		case fData:
			rmu.Lock()
			if stopped(stop) {
				rmu.Unlock()
				return errStopped
			}
			_, werr := s.out.Write(buf[1:n])
			if werr == nil {
				s.mu.Lock()
				s.recv += uint64(n - 1)
				s.mu.Unlock()
			}
			rmu.Unlock()
			if werr != nil {
				s.end() // nobody to hand it to: this end is gone
				return errEnded
			}
			if unacked += n - 1; unacked >= ackEvery {
				if err := ack(); err != nil {
					return err
				}
			}
		case fAck:
			// Acks stay true on any channel: the peer has those bytes
			if n < 9 {
				continue
			}
			s.mu.Lock()
			ok := s.ackTo(binary.BigEndian.Uint64(buf[1:9]))
			done := s.doneSending()
			s.mu.Unlock()
			if !ok {
				s.end()
				return errResume
			}
			if done {
				s.end()
				return errEnded
			}
		case fBye:
			// The peer's input ended: it has nothing more to say, and the connection is closing
			rmu.Lock()
			if stopped(stop) {
				rmu.Unlock()
				return errStopped
			}
			ack()
			s.closeOut()
			s.end()
			rmu.Unlock()
			return errEnded
		}
	}
}

// writeHello and readWelcome are the phone's half of the handshake on a new channel.
func writeJSONMsg(ch msgConn, kind byte, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return ch.WriteMsg(append([]byte{kind}, b...))
}

// readMsgTimeout reads one message, or fails after d (closing ch, which ends the read).
func readMsgTimeout(ch msgConn, d time.Duration) ([]byte, error) {
	type res struct {
		b   []byte
		err error
	}
	c := make(chan res, 1)
	go func() {
		buf := make([]byte, 64<<10)
		n, err := ch.ReadMsg(buf)
		c <- res{buf[:n], err}
	}()
	select {
	case r := <-c:
		return r.b, r.err
	case <-time.After(d):
		ch.Close()
		return nil, errors.New("no answer on the channel")
	}
}

// handshake opens or resumes the session sid on a new channel and returns the machine's welcome.
func handshake(ch msgConn, h helloMsg) (welcomeMsg, error) {
	var w welcomeMsg
	if err := writeJSONMsg(ch, fHello, h); err != nil {
		return w, err
	}
	b, err := readMsgTimeout(ch, 5*time.Second)
	if err != nil {
		return w, err
	}
	if len(b) == 0 || b[0] != fWelcome {
		return w, errors.New("unexpected first message")
	}
	return w, json.Unmarshal(b[1:], &w)
}
