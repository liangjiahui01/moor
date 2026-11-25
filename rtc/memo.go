package main

import (
	"encoding/json"
	"net"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// The tunnel memo: networks where no direct path opened to this machine, so the next connect from
// the same network goes straight to the tunnel instead of spending the direct try first. A network is
// known by its local IPv4 /24s (or IPv6 /64s when it has no IPv4); entries expire, so a direct path
// is tried again now and then.

const memoFor = 12 * time.Hour

var memoMu sync.Mutex

// networkKey names the network the phone is on now ("" when it can't tell).
func networkKey() string {
	addrs, err := localAddrs()
	if err != nil {
		return ""
	}
	var v4, v6 []string
	for _, a := range addrs {
		n, ok := a.(*net.IPNet)
		if !ok || n.IP.IsLoopback() || n.IP.IsLinkLocalUnicast() {
			continue
		}
		if ip := n.IP.To4(); ip != nil {
			if ip[0] == 198 && (ip[1] == 18 || ip[1] == 19) {
				continue // a VPN app's own interface: the same on every network
			}
			v4 = append(v4, net.IP(ip.Mask(net.CIDRMask(24, 32))).String())
		} else if n.IP.IsGlobalUnicast() {
			v6 = append(v6, n.IP.Mask(net.CIDRMask(64, 128)).String())
		}
	}
	key := v4
	if len(key) == 0 {
		key = v6
	}
	sort.Strings(key)
	return strings.Join(key, ",")
}

func readMemo(path string) map[string]int64 {
	m := map[string]int64{}
	if b, err := os.ReadFile(path); err == nil {
		json.Unmarshal(b, &m)
	}
	return m
}

// tunnelRemembered: the last connect from this network found no direct path, not long ago.
func tunnelRemembered(path, key string) bool {
	if path == "" || key == "" {
		return false
	}
	memoMu.Lock()
	defer memoMu.Unlock()
	t, ok := readMemo(path)[key]
	return ok && time.Since(time.Unix(t, 0)) < memoFor
}

// rememberTunnel notes (tunnel true) or forgets that this network needs the tunnel.
func rememberTunnel(path, key string, tunnel bool) {
	if path == "" || key == "" {
		return
	}
	memoMu.Lock()
	defer memoMu.Unlock()
	m := readMemo(path)
	_, had := m[key]
	if tunnel {
		m[key] = time.Now().Unix()
	} else if !had {
		return
	} else {
		delete(m, key)
	}
	for k, t := range m {
		if time.Since(time.Unix(t, 0)) > memoFor {
			delete(m, k)
		}
	}
	b, _ := json.Marshal(m)
	tmp := path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		os.Rename(tmp, path)
	}
}

// connect finds a path: direct, or (with --pipe) the tunnel, straight away on a network that needed
// it last time. iceTimeout bounds the direct try.
func (d *dialer2) connect(timeout, iceTimeout time.Duration) (*path, error) {
	key := ""
	if d.pipe {
		key = networkKey()
		if d.route == "tunnel" || tunnelRemembered(d.memo, key) {
			if d.route != "tunnel" {
				logf("no direct path from this network last time, tunneling")
			}
			return d.openPipe()
		}
	}
	p, err := d.open(timeout, iceTimeout)
	if err == nil {
		rememberTunnel(d.memo, key, false)
		return p, nil
	}
	if !d.pipe {
		return nil, err
	}
	logf("no direct path (%v), tunneling", err)
	p, perr := d.openPipe()
	if perr == nil {
		rememberTunnel(d.memo, key, true)
	}
	return p, perr
}
