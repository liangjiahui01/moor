package main

import (
	"net"
	"strings"
	"testing"
)

func TestKeepCandidates(t *testing.T) {
	sdp := "v=0\r\na=candidate:1 1 udp 2130706431 10.199.197.183 5000 typ host\r\n" +
		"a=candidate:2 1 udp 1694498815 101.47.7.16 6000 typ srflx raddr 0.0.0.0 rport 6000\r\n" +
		"a=candidate:3 1 udp 2130706431 240e:473::1 7000 typ host\r\na=end-of-candidates\r\n"
	lan := keepCandidates(sdp, "lan")
	if !strings.Contains(lan, "10.199.197.183") || strings.Contains(lan, "101.47.7.16") || !strings.Contains(lan, "240e") {
		t.Fatalf("lan: %q", lan)
	}
	wan := keepCandidates(sdp, "wan")
	if strings.Contains(wan, "10.199.197.183") || !strings.Contains(wan, "101.47.7.16") || !strings.Contains(wan, "240e") {
		t.Fatalf("wan: %q", wan)
	}
	if keepCandidates(sdp, "auto") != sdp {
		t.Fatal("auto changed the description")
	}
	if localOK("wan", net.ParseIP("fdbd:ff1::1")) || !localOK("wan", net.ParseIP("100.82.87.212")) || !localOK("wan", net.ParseIP("240e::1")) {
		t.Error("wan local filter")
	}
	if localOK("lan", net.ParseIP("240e::1")) || !localOK("lan", net.ParseIP("fdbd:ff1::1")) || !localOK("lan", net.ParseIP("10.1.2.3")) {
		t.Error("lan local filter")
	}
	for _, a := range []string{"100.82.87.212", "198.18.0.1", "192.168.31.230", "fdbd:ff1::1"} {
		if !privateAddr(a) {
			t.Errorf("%s should be private", a)
		}
	}
	for _, a := range []string{"101.47.7.16", "188.253.121.27", "240e:473::1"} {
		if privateAddr(a) {
			t.Errorf("%s should be public", a)
		}
	}
}
