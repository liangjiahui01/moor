// moor-rtc carries Moor's SSH connections over a WebRTC data channel, so the phone and the
// machine talk directly (peer to peer) when their networks allow it.
//
//	moor-rtc relay --listen :80                 signaling server: introduces the two sides, carries no data
//	moor-rtc init --signal ws://HOST/ws         on the machine: create ~/.config/moor/rtc.json (room and key)
//	moor-rtc host                               on the machine: answer the phone, connect channels to sshd
//	moor-rtc dial --signal U --room R --key K   in the app: one SSH connection on stdin/stdout
//
// SSH runs inside the channel unchanged, so it stays end-to-end encrypted and host keys are
// checked as before. The shared key only signs the signaling messages; it never crosses the wire.
package main

import (
	"fmt"
	"os"
	"time"
)

var start = time.Now()

func logf(format string, a ...any) {
	fmt.Fprintf(os.Stderr, "%7.3f "+format+"\n", append([]any{time.Since(start).Seconds()}, a...)...)
}

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	args := os.Args[2:]
	switch os.Args[1] {
	case "relay":
		relayMain(args)
	case "init":
		initMain(args)
	case "host":
		hostMain(args)
	case "dial":
		dialMain(args)
	case "version":
		fmt.Println(version)
	default:
		usage()
	}
}

const version = "1"

func usage() {
	fmt.Fprintln(os.Stderr, `usage:
  moor-rtc relay [--listen :80]
  moor-rtc init --signal ws://HOST[:PORT]/ws [--config PATH]
  moor-rtc host [--config PATH] [--ssh 127.0.0.1:22]
  moor-rtc dial --signal URL --room ROOM --key KEY [--fallback HOST:PORT]`)
	os.Exit(2)
}
