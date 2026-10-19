package main

import (
	"net"
	"strings"
)

// Route choice for dial: "auto" takes the first path that works; "lan" keeps only private addresses
// (company network, home LAN, VPN), "wan" only public ones (peer to peer over the internet).

var (
	cgnat   = mustCIDR("100.64.0.0/10") // carrier NAT and many office networks
	fakeIPs = mustCIDR("198.18.0.0/15") // proxy apps' TUN (fake-ip)
)

func mustCIDR(s string) *net.IPNet {
	_, n, err := net.ParseCIDR(s)
	if err != nil {
		panic(err)
	}
	return n
}

func privateAddr(a string) bool {
	ip := net.ParseIP(a)
	if ip == nil {
		return true // an mDNS name: only reachable nearby
	}
	return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || cgnat.Contains(ip) || fakeIPs.Contains(ip)
}

func uniqueLocal(ip net.IP) bool {
	return ip.To4() == nil && len(ip) == net.IPv6len && ip[0]&0xfe == 0xfc
}

// localOK: which of this device's addresses ICE may use. A private IPv4 address still reaches the
// internet through NAT, so "wan" keeps those and only drops unique-local IPv6 (an office network's
// IPv6 can reach the machine's company IPv6 address without leaving the company). "lan" drops
// public addresses, so no check leaves through the internet.
func localOK(route string, ip net.IP) bool {
	switch route {
	case "lan":
		return privateAddr(ip.String())
	case "wan":
		return !uniqueLocal(ip)
	}
	return true
}

// wantAddr: the candidates we tell the machine about (ours) and keep from its offer (theirs).
// For "lan" the machine's IPv6 addresses stay: they are only reached from our private IPv6.
func wantAddr(route, a string) bool {
	switch route {
	case "lan":
		return privateAddr(a)
	case "wan":
		return !privateAddr(a)
	}
	return true
}

func wantRemote(route, a string) bool {
	if route == "lan" {
		if ip := net.ParseIP(a); ip != nil && ip.To4() == nil {
			return true
		}
	}
	return wantAddr(route, a)
}

// keepCandidates drops the description's candidates that the route rules out.
func keepCandidates(sdp, route string) string {
	if route != "lan" && route != "wan" {
		return sdp
	}
	lines := strings.Split(sdp, "\r\n")
	out := lines[:0]
	for _, l := range lines {
		if strings.HasPrefix(l, "a=candidate:") {
			if f := strings.Fields(l); len(f) > 5 && !wantRemote(route, f[4]) {
				continue
			}
		}
		out = append(out, l)
	}
	return strings.Join(out, "\r\n")
}
