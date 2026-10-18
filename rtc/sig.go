package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/ice/v4"
	"github.com/pion/webrtc/v4"
)

// Msg is one signaling message, all through the relay:
//
//	phone   → machine  hello   (signed with id and time)
//	machine → phone    offer   (the machine starts ICE: in tests, paths only opened this way round)
//	phone   → machine  answer
type Msg struct {
	T   string `json:"t"`             // hello, offer, answer, error
	ID  string `json:"id,omitempty"`  // one per connection attempt
	SDP string `json:"sdp,omitempty"` // session description (all candidates included)
	TS  int64  `json:"ts,omitempty"`  // unix seconds, hello only
	MAC string `json:"mac,omitempty"` // HMAC-SHA256 with the shared key
	E   string `json:"e,omitempty"`   // error text
}

func mac(key []byte, parts ...string) string {
	h := hmac.New(sha256.New, key)
	h.Write([]byte(strings.Join(parts, "\n")))
	return base64.RawURLEncoding.EncodeToString(h.Sum(nil))
}

func helloMAC(key []byte, m Msg) string { return mac(key, "hello", m.ID, strconv.FormatInt(m.TS, 10)) }

// sdpMAC signs an offer or answer; the type is part of the signature.
func sdpMAC(key []byte, m Msg) string { return mac(key, m.T, m.ID, m.SDP) }

func validMAC(got, want string) bool { return hmac.Equal([]byte(got), []byte(want)) }

func randID(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// Config is the machine's rtc.json; the same three values reach the phone in the pairing link.
type Config struct {
	Signal string `json:"signal"`
	Room   string `json:"room"`
	Key    string `json:"key"`
}

func defaultConfigPath() string {
	if d := os.Getenv("XDG_CONFIG_HOME"); d != "" {
		return filepath.Join(d, "moor", "rtc.json")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "moor", "rtc.json")
}

func loadConfig(path string) (Config, error) {
	var c Config
	b, err := os.ReadFile(path)
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, err
	}
	if c.Signal == "" || c.Room == "" || c.Key == "" {
		return c, errors.New("rtc.json needs signal, room and key")
	}
	return c, nil
}

func decodeKey(k string) ([]byte, error) {
	b, err := base64.RawURLEncoding.DecodeString(k)
	if err != nil || len(b) < 16 {
		return nil, errors.New("bad key")
	}
	return b, nil
}

func signalURL(base, role, room string) string {
	sep := "?"
	if strings.Contains(base, "?") {
		sep = "&"
	}
	return fmt.Sprintf("%s%srole=%s&room=%s", base, sep, role, room)
}

// wsConn serializes writes, which gorilla requires.
type wsConn struct {
	*websocket.Conn
	mu sync.Mutex
}

func (c *wsConn) send(m Msg) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return c.WriteJSON(m)
}

func (c *wsConn) ping() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second))
}

var dialer = websocket.Dialer{HandshakeTimeout: 8 * time.Second, NetDialContext: (&net.Dialer{Timeout: 8 * time.Second}).DialContext}

// Public STUN servers tell each side its address on the internet.
var defaultSTUN = "stun:stun.l.google.com:19302,stun:stun.miwifi.com:3478,stun:stun.cloudflare.com:3478"

func newAPI() *webrtc.API {
	se := webrtc.SettingEngine{}
	se.DetachDataChannels()
	se.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	// Notice a dead path within seconds, so SSH reconnects instead of hanging
	se.SetICETimeouts(4*time.Second, 8*time.Second, 2*time.Second)
	// Take the first path that works: by default the machine waits 0.5 s (srflx) or 1 s (prflx)
	// for a better pair before choosing, and the first to answer is usually the fastest anyway
	se.SetSrflxAcceptanceMinWait(0)
	se.SetPrflxAcceptanceMinWait(0)
	return webrtc.NewAPI(webrtc.WithSettingEngine(se))
}

func iceConfig(stun string) webrtc.Configuration {
	var urls []string
	for _, u := range strings.Split(stun, ",") {
		if u = strings.TrimSpace(u); u != "" {
			urls = append(urls, u)
		}
	}
	return webrtc.Configuration{ICEServers: []webrtc.ICEServer{{URLs: urls}}}
}

func describePair(pc *webrtc.PeerConnection) string {
	sctp := pc.SCTP()
	if sctp == nil {
		return "?"
	}
	p, err := sctp.Transport().ICETransport().GetSelectedCandidatePair()
	if err != nil || p == nil {
		return "?"
	}
	return fmt.Sprintf("%s %s:%d <-> %s %s:%d", p.Local.Typ, p.Local.Address, p.Local.Port, p.Remote.Typ, p.Remote.Address, p.Remote.Port)
}

// gather waits until all local candidates are in the description (no trickle: one message each way).
func gather(pc *webrtc.PeerConnection, max time.Duration) {
	select {
	case <-webrtc.GatheringCompletePromise(pc):
	case <-time.After(max):
	}
}
