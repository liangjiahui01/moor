package main

import (
	"io"
	"sync"
	"time"

	"github.com/pion/datachannel"
	"github.com/pion/webrtc/v4"
)

const (
	chunk     = 16 << 10 // bytes per data channel message
	highWater = 1 << 20  // pause writing above this much unsent data
)

// channel turns a detached data channel into an io.ReadWriteCloser with backpressure.
type channel struct {
	dc  *webrtc.DataChannel
	rwc datachannel.ReadWriteCloser
	low chan struct{}
}

func newChannel(dc *webrtc.DataChannel) (*channel, error) {
	rwc, err := dc.Detach()
	if err != nil {
		return nil, err
	}
	c := &channel{dc: dc, rwc: rwc, low: make(chan struct{}, 1)}
	dc.SetBufferedAmountLowThreshold(highWater / 4)
	dc.OnBufferedAmountLow(func() {
		select {
		case c.low <- struct{}{}:
		default:
		}
	})
	return c, nil
}

func (c *channel) Read(p []byte) (int, error) { return c.rwc.Read(p) }

func (c *channel) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		n := min(len(p), chunk)
		for c.dc.BufferedAmount() > highWater {
			select {
			case <-c.low:
			case <-time.After(time.Second):
			}
		}
		w, err := c.rwc.Write(p[:n])
		total += w
		if err != nil {
			return total, err
		}
		p = p[n:]
	}
	return total, nil
}

func (c *channel) Close() error { return c.rwc.Close() }

// join copies both ways until either side ends, then closes both.
func join(a io.ReadWriteCloser, b io.ReadWriteCloser) {
	var once sync.Once
	closeBoth := func() { a.Close(); b.Close() }
	done := make(chan struct{}, 2)
	cp := func(dst io.Writer, src io.Reader) {
		buf := make([]byte, 64<<10) // a data channel message must fit in one read
		io.CopyBuffer(dst, src, buf)
		once.Do(closeBoth)
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	<-done
}
