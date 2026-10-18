package main

import (
	"encoding/json"
	"errors"
	"flag"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"
)

func initMain(args []string) {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	signal := fs.String("signal", "", "relay address, e.g. ws://203.0.113.5/ws")
	path := fs.String("config", defaultConfigPath(), "config file")
	fs.Parse(args)
	if *signal == "" {
		logf("--signal is required")
		os.Exit(2)
	}
	c := Config{Signal: *signal, Room: randID(16), Key: randID(32)}
	if old, err := loadConfig(*path); err == nil {
		c.Room, c.Key = old.Room, old.Key // keep pairings working when only the relay moves
	}
	b, _ := json.MarshalIndent(c, "", "  ")
	os.MkdirAll(filepath.Dir(*path), 0o700)
	if err := os.WriteFile(*path, append(b, '\n'), 0o600); err != nil {
		logf("%v", err)
		os.Exit(1)
	}
	logf("wrote %s; start `moor-rtc host`, then pair again with `moor pair` so the phone learns it", *path)
}

type host struct {
	cfg    Config
	key    []byte
	ssh    string
	stun   string
	api    *webrtc.API
	mu     sync.Mutex
	seen   map[string]time.Time // offer ids already answered
	active int
}

func hostMain(args []string) {
	fs := flag.NewFlagSet("host", flag.ExitOnError)
	path := fs.String("config", defaultConfigPath(), "config file")
	ssh := fs.String("ssh", "127.0.0.1:22", "sshd to connect channels to")
	stun := fs.String("stun", defaultSTUN, "STUN servers")
	fs.Parse(args)
	cfg, err := loadConfig(*path)
	if err != nil {
		logf("config: %v", err)
		os.Exit(1)
	}
	key, err := decodeKey(cfg.Key)
	if err != nil {
		logf("config: %v", err)
		os.Exit(1)
	}
	h := &host{cfg: cfg, key: key, ssh: *ssh, stun: *stun, api: newAPI(), seen: map[string]time.Time{}}
	backoff := time.Second
	for {
		began := time.Now()
		err := h.session()
		logf("relay connection ended: %v", err)
		if time.Since(began) > time.Minute {
			backoff = time.Second
		}
		time.Sleep(backoff)
		backoff = min(backoff*2, 30*time.Second)
	}
}

// session holds one connection to the relay and serves phones until it drops.
func (h *host) session() error {
	hdr := http.Header{}
	hdr.Set(hostAuthHeader, hostAuth(h.key, h.cfg.Room))
	ws, resp, err := dialer.Dial(signalURL(h.cfg.Signal, "host", h.cfg.Room), hdr)
	if resp != nil && resp.StatusCode == http.StatusForbidden {
		return errors.New("the relay has this room pinned to another machine's key")
	}
	if err != nil {
		return err
	}
	c := &wsConn{Conn: ws}
	defer c.Close()
	logf("connected to relay")
	c.SetReadDeadline(time.Now().Add(70 * time.Second))
	c.SetPingHandler(func(s string) error {
		c.SetReadDeadline(time.Now().Add(70 * time.Second))
		c.mu.Lock()
		defer c.mu.Unlock()
		return c.WriteControl(websocket.PongMessage, []byte(s), time.Now().Add(10*time.Second))
	})
	var mu sync.Mutex
	waiting := map[string]chan Msg{} // answers by id
	for {
		var m Msg
		if err := c.ReadJSON(&m); err != nil {
			return err
		}
		c.SetReadDeadline(time.Now().Add(70 * time.Second))
		switch m.T {
		case "hello":
			if !h.accept(m) {
				continue
			}
			ch := make(chan Msg, 1)
			mu.Lock()
			waiting[m.ID] = ch
			mu.Unlock()
			go func(m Msg) {
				h.connect(c, m, ch)
				mu.Lock()
				delete(waiting, m.ID)
				mu.Unlock()
			}(m)
		case "answer":
			mu.Lock()
			ch := waiting[m.ID]
			mu.Unlock()
			if ch != nil {
				select {
				case ch <- m:
				default:
				}
			}
		}
	}
}

func (h *host) accept(m Msg) bool {
	if !validMAC(m.MAC, helloMAC(h.key, m)) {
		logf("%s: bad signature, ignored", m.ID)
		return false
	}
	now := time.Now()
	if d := now.Unix() - m.TS; d > 120 || d < -120 {
		logf("%s: stale (%ds), ignored", m.ID, d)
		return false
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for id, t := range h.seen {
		if now.Sub(t) > 5*time.Minute {
			delete(h.seen, id)
		}
	}
	if _, dup := h.seen[m.ID]; dup || h.active >= 32 {
		return false
	}
	h.seen[m.ID] = now
	return true
}

// connect offers a data channel to the phone and, once it opens, joins it to sshd.
func (h *host) connect(c *wsConn, hello Msg, answers chan Msg) {
	id := hello.ID
	pc, err := h.api.NewPeerConnection(iceConfig(h.stun))
	if err != nil {
		logf("%s: %v", id, err)
		return
	}
	h.mu.Lock()
	h.active++
	h.mu.Unlock()
	var once sync.Once
	finish := func() {
		once.Do(func() {
			pc.Close()
			h.mu.Lock()
			h.active--
			h.mu.Unlock()
		})
	}
	pc.OnICEConnectionStateChange(func(s webrtc.ICEConnectionState) {
		logf("%s: ice %s", id, s)
		if s == webrtc.ICEConnectionStateFailed || s == webrtc.ICEConnectionStateClosed {
			finish()
		}
	})
	dc, err := pc.CreateDataChannel("ssh", nil)
	if err != nil {
		finish()
		return
	}
	opened := make(chan struct{})
	dc.OnOpen(func() {
		close(opened)
		ch, err := newChannel(dc)
		if err != nil {
			finish()
			return
		}
		tcp, err := net.DialTimeout("tcp", h.ssh, 5*time.Second)
		if err != nil {
			logf("%s: sshd: %v", id, err)
			ch.Close()
			finish()
			return
		}
		logf("%s: connected, %s", id, describePair(pc))
		go logRTT(pc, id, opened)
		go func() {
			logf("%s: closed (%s; a=channel b=sshd)", id, join(ch, tcp))
			finish()
		}()
	})
	offer, err := pc.CreateOffer(nil)
	if err == nil {
		err = pc.SetLocalDescription(offer)
	}
	if err != nil {
		logf("%s: %v", id, err)
		finish()
		return
	}
	t0 := time.Now()
	gather(pc, 3*time.Second)
	logf("%s: offer ready in %d ms", id, time.Since(t0).Milliseconds())
	o := Msg{T: "offer", ID: id, SDP: pc.LocalDescription().SDP}
	o.MAC = sdpMAC(h.key, o)
	if err := c.send(o); err != nil {
		finish()
		return
	}
	var a Msg
	select {
	case a = <-answers:
	case <-time.After(15 * time.Second):
		logf("%s: no answer, dropped", id)
		finish()
		return
	}
	if !validMAC(a.MAC, sdpMAC(h.key, a)) {
		logf("%s: answer has a bad signature", id)
		finish()
		return
	}
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: a.SDP}); err != nil {
		logf("%s: %v", id, err)
		finish()
		return
	}
	// The phone gives up after its own timeout; don't keep a half-open connection
	select {
	case <-opened:
	case <-time.After(30 * time.Second):
		logf("%s: no channel, dropped", id)
		finish()
	}
}

// logRTT notes the path's round trip (from ICE keepalives) every 30 s while the connection lives.
func logRTT(pc *webrtc.PeerConnection, id string, _ chan struct{}) {
	for {
		time.Sleep(30 * time.Second)
		st := pc.ICEConnectionState()
		if st == webrtc.ICEConnectionStateClosed || st == webrtc.ICEConnectionStateFailed {
			return
		}
		sctp := pc.SCTP()
		if sctp == nil {
			return
		}
		if s, ok := sctp.Transport().ICETransport().GetSelectedCandidatePairStats(); ok {
			logf("%s: rtt %.0f ms", id, s.CurrentRoundTripTime*1000)
		}
	}
}
