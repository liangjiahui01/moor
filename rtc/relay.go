package main

import (
	"flag"
	"net/http"
	"regexp"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// The relay pairs the machine (host) and phones (clients) of one room and forwards offers and
// answers between them. It keeps nothing on disk and never sees SSH traffic.

var roomRE = regexp.MustCompile(`^[A-Za-z0-9_-]{16,64}$`)

type room struct {
	host    *wsConn
	clients map[string]*wsConn // by offer id
}

type relay struct {
	mu    sync.Mutex
	rooms map[string]*room
}

var upgrader = websocket.Upgrader{ReadBufferSize: 16 << 10, WriteBufferSize: 16 << 10, CheckOrigin: func(*http.Request) bool { return true }}

func relayMain(args []string) {
	fs := flag.NewFlagSet("relay", flag.ExitOnError)
	listen := fs.String("listen", ":80", "address to listen on")
	fs.Parse(args)
	r := &relay{rooms: map[string]*room{}}
	http.HandleFunc("/ws", r.serve)
	http.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("moor-rtc relay\n")) })
	logf("relay listening on %s", *listen)
	srv := &http.Server{Addr: *listen, ReadHeaderTimeout: 10 * time.Second}
	logf("%v", srv.ListenAndServe())
}

func (r *relay) roomFor(id string) *room {
	rm := r.rooms[id]
	if rm == nil {
		rm = &room{clients: map[string]*wsConn{}}
		r.rooms[id] = rm
	}
	return rm
}

func (r *relay) dropIfEmpty(id string) {
	if rm := r.rooms[id]; rm != nil && rm.host == nil && len(rm.clients) == 0 {
		delete(r.rooms, id)
	}
}

func (r *relay) serve(w http.ResponseWriter, req *http.Request) {
	role, id := req.URL.Query().Get("role"), req.URL.Query().Get("room")
	if (role != "host" && role != "client") || !roomRE.MatchString(id) {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	r.mu.Lock()
	tooMany := len(r.rooms) > 10000
	r.mu.Unlock()
	if tooMany {
		http.Error(w, "busy", http.StatusServiceUnavailable)
		return
	}
	ws, err := upgrader.Upgrade(w, req, nil)
	if err != nil {
		return
	}
	c := &wsConn{Conn: ws}
	c.SetReadLimit(64 << 10)
	c.SetReadDeadline(time.Now().Add(70 * time.Second))
	c.SetPongHandler(func(string) error { c.SetReadDeadline(time.Now().Add(70 * time.Second)); return nil })
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(25 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				if c.ping() != nil {
					return
				}
			}
		}
	}()
	defer close(stop)
	defer c.Close()
	if role == "host" {
		r.serveHost(id, c)
	} else {
		r.serveClient(id, c)
	}
}

func (r *relay) serveHost(id string, c *wsConn) {
	r.mu.Lock()
	rm := r.roomFor(id)
	if rm.host != nil {
		rm.host.Close() // a restarted host replaces the old connection
	}
	rm.host = c
	r.mu.Unlock()
	logf("host joined room %s…", id[:6])
	defer func() {
		r.mu.Lock()
		if rm.host == c {
			rm.host = nil
		}
		r.dropIfEmpty(id)
		r.mu.Unlock()
		logf("host left room %s…", id[:6])
	}()
	for {
		var m Msg
		if err := c.ReadJSON(&m); err != nil {
			return
		}
		c.SetReadDeadline(time.Now().Add(70 * time.Second))
		r.mu.Lock()
		to := rm.clients[m.ID]
		r.mu.Unlock()
		if to != nil {
			to.send(m)
		}
	}
}

func (r *relay) serveClient(id string, c *wsConn) {
	var mine []string
	defer func() {
		r.mu.Lock()
		if rm := r.rooms[id]; rm != nil {
			for _, k := range mine {
				if rm.clients[k] == c {
					delete(rm.clients, k)
				}
			}
		}
		r.dropIfEmpty(id)
		r.mu.Unlock()
	}()
	for {
		var m Msg
		if err := c.ReadJSON(&m); err != nil {
			return
		}
		c.SetReadDeadline(time.Now().Add(70 * time.Second))
		if (m.T != "hello" && m.T != "answer") || m.ID == "" {
			continue
		}
		r.mu.Lock()
		rm := r.roomFor(id)
		if m.T == "hello" {
			if len(mine) >= 32 {
				r.mu.Unlock()
				continue
			}
			rm.clients[m.ID] = c
			mine = append(mine, m.ID)
		}
		host := rm.host
		r.mu.Unlock()
		if host == nil {
			c.send(Msg{T: "error", ID: m.ID, E: "machine offline"})
			continue
		}
		host.send(m)
	}
}
