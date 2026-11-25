package main

// Tunnels: a resumable stream through the relay when no direct path opens.
//
// A jump host behind a NAT that can't be punched through (a company network) is still reachable by
// the phone through the relay's own WebSocket port, which both sides already connect out to:
//
//	phone   → relay  role=pipe, then {"t":"pipe", id, ts, mac} signed like a hello
//	relay   → machine the same message on the machine's signaling connection
//	machine → relay  role=hpipe&id=…, with the machine's proof (as for role=host)
//
// The relay then copies messages between the two, and the resumable stream (resume.go) runs on top:
// SSH inside stays end-to-end encrypted and checked against the machine's host key. The phone sends
// a ping message every couple of seconds; the machine echoes it, which measures the round trip and
// notices a dead tunnel.

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	fPing = 'p' // phone → machine: 8-byte send time; machines that don't know it ignore it
	fPong = 'q' // machine → phone: the same bytes back
)

// wsMsg is a WebSocket seen as messages (msgConn).
type wsMsg struct {
	c    *wsConn
	once sync.Once
	// pong, on the phone: called with the round trip when an echo comes back
	pong func(time.Duration)
}

func (w *wsMsg) ReadMsg(p []byte) (int, error) {
	for {
		t, b, err := w.c.ReadMessage()
		if err != nil {
			return 0, err
		}
		if t == websocket.TextMessage {
			// The relay's own word: the machine can't be reached
			var m Msg
			if json.Unmarshal(b, &m) == nil && m.E != "" {
				return 0, errors.New(m.E)
			}
			continue
		}
		if len(b) > len(p) {
			return 0, errors.New("message too long")
		}
		return copy(p, b), nil
	}
}

func (w *wsMsg) WriteMsg(p []byte) error {
	w.c.mu.Lock()
	defer w.c.mu.Unlock()
	w.c.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return w.c.WriteMessage(websocket.BinaryMessage, p)
}

func (w *wsMsg) Close() error {
	w.once.Do(func() { w.c.Close() })
	return nil
}

// gotPong is told an echo of one of our pings.
func (w *wsMsg) gotPong(b []byte) {
	if w.pong != nil && len(b) >= 9 {
		sent := time.Unix(0, int64(binary.BigEndian.Uint64(b[1:9])))
		w.pong(time.Since(sent))
	}
}

// ---- relay ----

type pendingPipe struct {
	host chan *wsConn
	done chan struct{}
}

// servePipe is the phone's end: it asks the machine for the other end and joins the two.
func (r *relay) servePipe(id string, c *wsConn) {
	c.SetReadDeadline(time.Now().Add(10 * time.Second))
	var m Msg
	if err := c.ReadJSON(&m); err != nil || m.T != "pipe" || m.ID == "" || len(m.ID) > 32 {
		return
	}
	key := id + "/" + m.ID
	p := &pendingPipe{host: make(chan *wsConn, 1), done: make(chan struct{})}
	r.mu.Lock()
	rm := r.rooms[id]
	var host *wsConn
	if rm != nil {
		host = rm.host
	}
	if host == nil || len(r.pipes) > 1000 || r.pipes[key] != nil {
		r.mu.Unlock()
		c.send(Msg{T: "error", ID: m.ID, E: "machine offline"})
		return
	}
	r.pipes[key] = p
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.pipes, key)
		r.mu.Unlock()
		close(p.done)
	}()
	host.send(m)
	var other *wsConn
	select {
	case other = <-p.host:
	case <-time.After(6 * time.Second):
		c.send(Msg{T: "error", ID: m.ID, E: "machine didn't answer"})
		return
	}
	r.mu.Lock()
	delete(r.pipes, key) // joined: the id can't be taken again
	r.mu.Unlock()
	logf("room %s…: tunnel %s joined", id[:6], m.ID)
	copyMsgs(c, other)
	logf("room %s…: tunnel %s closed", id[:6], m.ID)
}

// serveHostPipe is the machine's end of a tunnel a phone asked for.
func (r *relay) serveHostPipe(id, pid string, c *wsConn) {
	r.mu.Lock()
	p := r.pipes[id+"/"+pid]
	r.mu.Unlock()
	if p == nil {
		return
	}
	select {
	case p.host <- c:
	default:
		return
	}
	<-p.done
}

// copyMsgs copies messages both ways until either side ends, then closes both.
func copyMsgs(a, b *wsConn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src *wsConn) {
		defer func() { done <- struct{}{} }()
		for {
			t, msg, err := src.ReadMessage()
			if err != nil {
				return
			}
			src.SetReadDeadline(time.Now().Add(70 * time.Second))
			dst.mu.Lock()
			dst.SetWriteDeadline(time.Now().Add(15 * time.Second))
			err = dst.WriteMessage(t, msg)
			dst.mu.Unlock()
			if err != nil {
				return
			}
		}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	a.Close()
	b.Close()
	<-done
}

// ---- machine ----

// openPipe answers a phone's tunnel request: joins it at the relay and serves the session on it.
func (h *host) openPipe(m Msg) {
	hdr := http.Header{}
	hdr.Set(hostAuthHeader, hostAuth(h.key, h.cfg.Room))
	ws, _, err := dialer.Dial(signalURL(h.cfg.Signal, "hpipe", h.cfg.Room)+"&id="+m.ID, hdr)
	if err != nil {
		logf("%s: tunnel: %v", m.ID, err)
		return
	}
	c := &wsConn{Conn: ws}
	c.SetReadLimit(64 << 10)
	mc := &wsMsg{c: c}
	b, err := readMsgTimeout(mc, 8*time.Second)
	if err != nil || len(b) < 2 || b[0] != fHello || b[1] != '{' {
		mc.Close()
		return
	}
	logf("%s: tunnel through the relay", m.ID)
	h.resumable(m.ID, mc, b[1:], func() {})
}

// ---- phone ----

// openPipe opens a tunnel to the machine through the relay.
func (d *dialer2) openPipe() (*path, error) {
	key, err := decodeKey(d.key)
	if err != nil {
		return nil, err
	}
	ws, _, err := dialer.Dial(signalURL(d.signal, "pipe", d.room), nil)
	if err != nil {
		return nil, err
	}
	c := &wsConn{Conn: ws}
	c.SetReadLimit(64 << 10)
	m := Msg{T: "pipe", ID: randID(9), TS: time.Now().Unix()}
	m.MAC = helloMAC(key, m)
	if err := c.send(m); err != nil {
		c.Close()
		return nil, err
	}
	failed := make(chan struct{})
	var failOnce sync.Once
	fail := func() { failOnce.Do(func() { close(failed) }) }
	var lastPong time.Time
	var pmu sync.Mutex
	mc := &wsMsg{c: c}
	mc.pong = func(rtt time.Duration) {
		pmu.Lock()
		lastPong = time.Now()
		pmu.Unlock()
		reportPipeRTT(rtt)
	}
	// The relay answers with a text error when the machine can't be reached; otherwise the next
	// thing is the machine's welcome, which the caller reads
	p := &path{ch: mc, failed: failed, label: resumeLabel}
	p.close = func() { fail(); mc.Close() }
	go func() {
		pmu.Lock()
		lastPong = time.Now()
		pmu.Unlock()
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		msg := make([]byte, 9)
		msg[0] = fPing
		for {
			select {
			case <-failed:
				return
			case <-t.C:
			}
			pmu.Lock()
			quiet := time.Since(lastPong)
			pmu.Unlock()
			if quiet > 6*time.Second {
				logf("tunnel quiet, trying a new path")
				fail()
				mc.Close()
				return
			}
			binary.BigEndian.PutUint64(msg[1:], uint64(time.Now().UnixNano()))
			if mc.WriteMsg(msg) != nil {
				fail()
				return
			}
		}
	}()
	logf("route direct pipe 0.0.0.0:0 <-> pipe %s", relayHost(d.signal))
	return p, nil
}

var pipeRTT struct {
	sync.Mutex
	last time.Time
}

// reportPipeRTT logs the tunnel's round trip now and then, for the app to show.
func reportPipeRTT(rtt time.Duration) {
	pipeRTT.Lock()
	defer pipeRTT.Unlock()
	if time.Since(pipeRTT.last) < 10*time.Second {
		return
	}
	pipeRTT.last = time.Now()
	logf("rtt %d ms", rtt.Milliseconds())
}

func relayHost(signal string) string {
	u, err := url.Parse(signal)
	if err != nil || u.Host == "" {
		return "relay:0"
	}
	if u.Port() == "" {
		return u.Host + ":80"
	}
	return u.Host
}
