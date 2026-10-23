package main

import (
	"bytes"
	"errors"
	"io"
	"math/rand"
	"sync"
	"testing"
	"time"
)

// memConn is one end of an in-memory path that can die: messages still in flight are lost then.
type memConn struct {
	in   chan []byte
	peer *memConn
	dead chan struct{}
	once *sync.Once
}

func memPair() (*memConn, *memConn) {
	dead := make(chan struct{})
	once := &sync.Once{}
	a := &memConn{in: make(chan []byte, 64), dead: dead, once: once}
	b := &memConn{in: make(chan []byte, 64), dead: dead, once: once}
	a.peer, b.peer = b, a
	return a, b
}

func (c *memConn) ReadMsg(p []byte) (int, error) {
	select {
	case <-c.dead:
		return 0, io.EOF
	case m := <-c.in:
		select {
		case <-c.dead: // lost with the path
			return 0, io.EOF
		default:
		}
		return copy(p, m), nil
	}
}

func (c *memConn) WriteMsg(p []byte) error {
	m := append([]byte(nil), p...)
	select {
	case <-c.dead:
		return io.ErrClosedPipe
	case c.peer.in <- m:
		return nil
	}
}

func (c *memConn) Close() error {
	c.once.Do(func() { close(c.dead) })
	return nil
}

type safeBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *safeBuf) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *safeBuf) Bytes() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]byte(nil), s.b.Bytes()...)
}

func randBytes(r *rand.Rand, n int) []byte {
	b := make([]byte, n)
	r.Read(b)
	return b
}

// The machine's output arrives whole and in order through many path deaths, and the phone's output
// arrives as a prefix of what it sent; both ends finish after the machine's input ends.
func TestStreamSurvivesPathDeaths(t *testing.T) {
	for seed := int64(1); seed <= 20; seed++ {
		r := rand.New(rand.NewSource(seed))
		hostIn := randBytes(r, 3<<20+r.Intn(1<<20))
		phoneIn := randBytes(r, 2<<20)
		var phoneOut, hostOut safeBuf
		phone := newStream(&phoneOut, func() {})
		host := newStream(&hostOut, func() {})
		go host.pump(bytes.NewReader(hostIn))
		pr, pw := io.Pipe() // the phone's input stays open, as SSH's does
		go func() { pw.Write(phoneIn) }()
		go phone.pump(pr)

		deaths := 0
		deadline := time.Now().Add(20 * time.Second)
		for !(phone.isEnded() && host.isEnded()) {
			if time.Now().After(deadline) {
				t.Fatalf("seed %d: not finished after %d deaths (phone got %d/%d)", seed, deaths, len(phoneOut.Bytes()), len(hostIn))
			}
			a, b := memPair()
			// The hello and welcome: each side tells what it has
			pRecv, hRecv := phone.received(), host.received()
			errs := make(chan error, 2)
			go func() { errs <- phone.serve(a, hRecv, nil) }()
			go func() { errs <- host.serve(b, pRecv, nil) }()
			time.AfterFunc(time.Duration(1+r.Intn(15))*time.Millisecond, func() { a.Close() })
			for i := 0; i < 2; i++ {
				if err := <-errs; errors.Is(err, errResume) {
					t.Fatalf("seed %d: %v", seed, err)
				}
			}
			deaths++
		}
		if !bytes.Equal(phoneOut.Bytes(), hostIn) {
			t.Fatalf("seed %d: phone got %d bytes, want %d (or content differs)", seed, len(phoneOut.Bytes()), len(hostIn))
		}
		got := hostOut.Bytes()
		if !bytes.Equal(got, phoneIn[:len(got)]) {
			t.Fatalf("seed %d: machine's input is not a prefix of what the phone sent", seed)
		}
		pw.Close()
		t.Logf("seed %d: %d paths, machine got %d of %d", seed, deaths, len(got), len(phoneIn))
	}
}

// A resume from a point the sender no longer holds is refused instead of corrupting the stream.
func TestStreamRefusesLostData(t *testing.T) {
	var out safeBuf
	s := newStream(&out, func() {})
	s.mu.Lock()
	s.buf = []byte("abc")
	s.base = 10
	s.mu.Unlock()
	a, _ := memPair()
	if err := s.serve(a, 5, nil); !errors.Is(err, errResume) {
		t.Fatalf("got %v, want errResume", err)
	}
	if err := s.serve(a, 20, nil); !errors.Is(err, errResume) {
		t.Fatalf("got %v, want errResume", err)
	}
}
