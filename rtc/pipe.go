package main

import (
	"fmt"
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
	dc     *webrtc.DataChannel
	rwc    datachannel.ReadWriteCloser
	low    chan struct{}
	closed chan struct{}
	once   sync.Once
}

func newChannel(dc *webrtc.DataChannel) (*channel, error) {
	rwc, err := dc.Detach()
	if err != nil {
		return nil, err
	}
	c := &channel{dc: dc, rwc: rwc, low: make(chan struct{}, 1), closed: make(chan struct{})}
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

// ReadMsg reads one data channel message (p must hold it: up to chunk bytes).
func (c *channel) ReadMsg(p []byte) (int, error) { return c.rwc.Read(p) }

// WriteMsg sends p as one message (at most chunk bytes), waiting while too much is unsent.
func (c *channel) WriteMsg(p []byte) error {
	for c.dc.BufferedAmount() > highWater {
		select {
		case <-c.low:
		case <-c.closed:
			return io.ErrClosedPipe
		case <-time.After(time.Second):
		}
	}
	_, err := c.rwc.Write(p)
	return err
}

func (c *channel) Close() error {
	c.once.Do(func() { close(c.closed) })
	return c.rwc.Close()
}

// join copies both ways until either side ends, then closes both. It reports which direction
// ended first and why ("a<-b" means reading b or writing a stopped).
func join(a io.ReadWriteCloser, b io.ReadWriteCloser) string {
	var once sync.Once
	var why string
	done := make(chan struct{}, 2)
	cp := func(name string, dst io.Writer, src io.Reader) {
		buf := make([]byte, 64<<10) // a data channel message must fit in one read
		var err error
		for {
			n, rerr := src.Read(buf)
			if n > 0 {
				if _, werr := dst.Write(buf[:n]); werr != nil {
					err = fmt.Errorf("write: %w", werr)
					break
				}
			}
			if rerr != nil {
				err = fmt.Errorf("read: %w", rerr)
				break
			}
		}
		once.Do(func() {
			why = name + " " + err.Error()
			a.Close()
			b.Close()
		})
		done <- struct{}{}
	}
	go cp("a<-b", a, b)
	go cp("b<-a", b, a)
	<-done
	<-done
	return why
}
