package main

import (
	"context"
	"errors"
	"flag"
	"io"
	"net"
	"os"
	"time"

	"github.com/pion/webrtc/v4"
)

// stdio is this process's stdin and stdout as one stream: the app reads and writes SSH there.
type stdio struct{}

func (stdio) Read(p []byte) (int, error)  { return os.Stdin.Read(p) }
func (stdio) Write(p []byte) (int, error) { return os.Stdout.Write(p) }
func (stdio) Close() error                { os.Stdout.Close(); return nil }

func dialMain(args []string) {
	fs := flag.NewFlagSet("dial", flag.ExitOnError)
	signal := fs.String("signal", "", "relay address")
	roomID := fs.String("room", "", "room")
	keyStr := fs.String("key", "", "shared key")
	fallback := fs.String("fallback", "", "HOST:PORT to use when no direct path is found")
	stun := fs.String("stun", defaultSTUN, "STUN servers")
	timeout := fs.Duration("timeout", 10*time.Second, "how long to try a direct path")
	fs.Parse(args)
	if *keyStr == "" {
		*keyStr = os.Getenv("MOOR_RTC_KEY") // keeps the key off the command line
	}
	useBuiltinDNS()
	androidNetwork()

	err := direct(*signal, *roomID, *keyStr, *stun, *timeout)
	if err == nil {
		os.Exit(0)
	}
	if errors.Is(err, errEnded) {
		os.Exit(0)
	}
	logf("route none: %v", err)
	if *fallback == "" {
		os.Exit(1)
	}
	tcp, err := net.DialTimeout("tcp", *fallback, 10*time.Second)
	if err != nil {
		logf("fallback: %v", err)
		os.Exit(1)
	}
	if t, ok := tcp.(*net.TCPConn); ok {
		t.SetKeepAlive(true)
		t.SetKeepAlivePeriod(15 * time.Second)
	}
	logf("route relay %s", *fallback)
	logf("closed (%s; a=app b=fallback)", join(stdio{}, tcp))
}

var errEnded = errors.New("ended")

// direct asks the machine (through the relay) to offer a data channel, answers it and, once it
// is open, carries stdin/stdout over it until either side ends (returns errEnded then). Any error
// before the channel opens means: try the fallback.
func direct(signal, roomID, keyStr, stun string, timeout time.Duration) error {
	if signal == "" || roomID == "" || keyStr == "" {
		return errors.New("not configured")
	}
	key, err := decodeKey(keyStr)
	if err != nil {
		return err
	}
	deadline := time.Now().Add(timeout)
	ws, _, err := dialer.Dial(signalURL(signal, "client", roomID), nil)
	if err != nil {
		return err
	}
	c := &wsConn{Conn: ws}
	defer c.Close()
	hello := Msg{T: "hello", ID: randID(9), TS: time.Now().Unix()}
	hello.MAC = helloMAC(key, hello)
	if err := c.send(hello); err != nil {
		return err
	}
	c.SetReadDeadline(deadline)
	var o Msg
	for {
		if err := c.ReadJSON(&o); err != nil {
			return errors.New("no answer from the machine")
		}
		if o.ID == hello.ID {
			break
		}
	}
	if o.T == "error" {
		return errors.New(o.E)
	}
	if o.T != "offer" || !validMAC(o.MAC, sdpMAC(key, o)) {
		return errors.New("offer has a bad signature")
	}

	pc, err := newAPI().NewPeerConnection(iceConfig(stun))
	if err != nil {
		return err
	}
	opened := make(chan *webrtc.DataChannel, 1)
	failed := make(chan struct{})
	pc.OnICEConnectionStateChange(func(s webrtc.ICEConnectionState) {
		logf("ice %s", s)
		if s == webrtc.ICEConnectionStateFailed {
			select {
			case <-failed:
			default:
				close(failed)
			}
		}
	})
	pc.OnICECandidate(func(cand *webrtc.ICECandidate) {
		if cand != nil {
			logf("local %s %s %s:%d", cand.Typ, cand.Protocol, cand.Address, cand.Port)
		}
	})
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		dc.OnOpen(func() { opened <- dc })
	})
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: o.SDP}); err != nil {
		pc.Close()
		return err
	}
	ans, err := pc.CreateAnswer(nil)
	if err == nil {
		err = pc.SetLocalDescription(ans)
	}
	if err != nil {
		pc.Close()
		return err
	}
	gather(pc, 2500*time.Millisecond)
	a := Msg{T: "answer", ID: hello.ID, SDP: pc.LocalDescription().SDP}
	a.MAC = sdpMAC(key, a)
	if err := c.send(a); err != nil {
		pc.Close()
		return err
	}
	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()
	var dc *webrtc.DataChannel
	select {
	case dc = <-opened:
	case <-failed:
		pc.Close()
		return errors.New("no direct path")
	case <-ctx.Done():
		pc.Close()
		return errors.New("no direct path in time")
	}
	c.Close()
	ch, err := newChannel(dc)
	if err != nil {
		pc.Close()
		return err
	}
	logf("route direct %s", describePair(pc))
	// A dead path closes the channel, which ends the copy and so the SSH connection
	go func() {
		<-failed
		ch.Close()
	}()
	logf("closed (%s; a=app b=channel), ice %s", join(stdio{}, ch), pc.ICEConnectionState())
	pc.Close()
	return errEnded
}

// useBuiltinDNS: Android has no /etc/resolv.conf, so Go's resolver would find no server.
func useBuiltinDNS() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	servers := []string{"223.5.5.5:53", "119.29.29.29:53", "8.8.8.8:53"}
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		var last error
		for _, s := range servers {
			c, err := d.DialContext(ctx, network, s)
			if err == nil {
				return c, nil
			}
			last = err
		}
		return nil, last
	}}
}

var _ io.Closer = stdio{}
