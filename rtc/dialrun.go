package main

import (
	"errors"
	"os"
	"time"

	"github.com/pion/webrtc/v4"
)

// How long the phone looks for a new path before giving up the session (the app then reconnects
// from scratch). The machine keeps it a little longer (resumeWait).
const resumeGiveUp = 60 * time.Second

// A path that stops answering this long after ICE says disconnected is given up for a new one: a
// resume costs a second or two, waiting for ICE to declare it failed costs four more.
const quietWait = 1 * time.Second

// run carries stdin/stdout over the path and, when the machine resumes sessions, over new paths
// after one dies, so SSH on top never notices. It doesn't return.
func (d *dialer2) run(p *path) {
	if p.dc.Label() != resumeLabel {
		// A machine that doesn't resume: a dead path ends the process right away (closing the channel
		// can hang on a dead path), and the app reconnects as soon as SSH sees our stdout close
		go func() {
			<-p.failed
			logf("closed (path failed)")
			os.Exit(3)
		}()
		logf("closed (%s; a=app b=channel), ice %s", join(stdio{}, p.ch), p.pc.ICEConnectionState())
		p.pc.Close()
		os.Exit(0)
	}
	sid := randID(12)
	w, err := handshake(p.ch, helloMsg{SID: sid})
	if err != nil || w.Gone {
		logf("closed (no session: %v)", err)
		os.Exit(3)
	}
	logf("resumable session %s", sid)
	s := newStream(os.Stdout, func() { os.Stdout.Close() })
	go s.pump(os.Stdin)
	for {
		abort, stop := make(chan struct{}), make(chan struct{})
		go d.watch(p, abort, stop)
		err := s.serve(p.ch, w.Recv, abort)
		close(stop)
		go p.pc.Close() // can hang on a dead path
		if errors.Is(err, errEnded) {
			logf("closed (ended)")
			os.Exit(0)
		}
		if errors.Is(err, errResume) {
			logf("closed (%v)", err)
			os.Exit(3)
		}
		lost := time.Now()
		logf("path lost (%v), resuming", err)
		for try := 0; ; try++ {
			if time.Since(lost) > resumeGiveUp {
				logf("closed (no path for %s)", resumeGiveUp)
				os.Exit(3)
			}
			// Right after a network change the first tries often find nothing: keep them short
			iceWait := 3 * time.Second
			if try < 2 {
				iceWait = 1500 * time.Millisecond
			}
			np, err := d.open(10*time.Second, iceWait)
			if err != nil {
				logf("route none: %v", err)
				time.Sleep(300 * time.Millisecond)
				continue
			}
			if np.dc.Label() != resumeLabel {
				np.pc.Close()
				logf("closed (the machine no longer resumes)")
				os.Exit(3)
			}
			w, err = handshake(np.ch, helloMsg{SID: sid, Resume: true, Recv: s.received()})
			if err != nil {
				np.pc.Close()
				logf("resume: %v", err)
				continue
			}
			if w.Gone {
				np.pc.Close()
				logf("closed (session gone)")
				os.Exit(3)
			}
			p = np
			logf("resumed in %d ms", time.Since(lost).Milliseconds())
			break
		}
	}
}

// watch gives up the path (closing abort, which ends serve) when ICE fails, when it stays
// disconnected for quietWait, or when the app asks for a new path through the ctl file.
func (d *dialer2) watch(p *path, abort, stop chan struct{}) {
	var ctlTime time.Time
	if d.ctl != "" {
		if fi, err := os.Stat(d.ctl); err == nil {
			ctlTime = fi.ModTime()
		}
	}
	tick := time.NewTicker(250 * time.Millisecond)
	defer tick.Stop()
	var quiet <-chan time.Time
	for {
		select {
		case <-stop:
			return
		case <-p.failed:
			close(abort)
			return
		case st := <-p.state:
			switch st {
			case webrtc.ICEConnectionStateDisconnected:
				quiet = time.After(quietWait)
			case webrtc.ICEConnectionStateConnected, webrtc.ICEConnectionStateCompleted:
				quiet = nil
			}
		case <-quiet:
			logf("path quiet, trying a new one")
			close(abort)
			return
		case <-tick.C:
			if d.ctl == "" {
				continue
			}
			if fi, err := os.Stat(d.ctl); err == nil && !fi.ModTime().Equal(ctlTime) {
				logf("new path asked")
				close(abort)
				return
			}
		}
	}
}
