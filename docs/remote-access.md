# Away from the host's network

[中文](remote-access.zh-CN.md)

When the host sits behind NAT (an office network, a cloud VPC, a home router) and the phone is elsewhere, neither side can see the other's address. This page shows how a server with a public address connects the two, what each part does, and what it can see.

## Three parts

| Part | Runs on | Does |
|---|---|---|
| Relay, `moor-rtc relay` | A small server with a public address | Lets the two sides swap addresses for each connection: a few messages, a few KB. Never touches SSH traffic |
| Host side, `moor-rtc host` | Your host, always on | Waits on the relay for the phone, then connects the data channel to the local sshd |
| Phone side, `moor-rtc dial` | Bundled in the APK, run by the app | Gets the host's addresses through the relay, opens the direct path, runs SSH over it |

One connection:

```
phone ──hello──▶ relay ──▶ host
phone ◀──offer── relay ◀── host      (with the host's candidate addresses)
phone ──answer─▶ relay ──▶ host      (with the phone's candidate addresses)
phone ◀════ direct path (WebRTC data channel carrying SSH) ════▶ host
```

To save a trip, the host leaves a ready offer on the relay, which hands it to the next phone at once and tells the host to make another; the phone sends its answer right away and its candidate addresses as it finds them.

Each side asks public STUN servers for its address on the internet, puts it in the offer or answer, and both send packets to each other at once, which opens a path through each NAT (UDP hole punching). From then on the relay is out of the loop: terminal traffic flows only between phone and host.

## Security

- Offers and answers are signed with a shared key made at pairing (HMAC-SHA256). The key lives only in the host's `~/.config/moor/rtc.json` and on the phone, and never crosses the relay, so the relay can't forge messages or pose as either side.
- SSH runs unchanged inside the data channel: still end-to-end encrypted, and the phone checks the host key it recorded at pairing. The relay sees only the session descriptions (each side's candidate addresses), never the terminal.
- When the host joins the relay it shows a proof derived from the shared key (not the key itself), and the relay binds the room to the first host that showed one. Someone who only knows the room can neither pose as the host nor push it off.
- The relay writes nothing to disk; a restart forgets everything.

## Setup

You need a server with a public IP (1 CPU and 1 GB is plenty; traffic is tiny) with TCP 80 open, and a host that can reach the internet over UDP (most networks allow it).

### 1. Relay

Get `moor-rtc-linux-amd64` (or `arm64`) from [Releases](https://github.com/liangjiahui01/moor/releases), put it at `/usr/local/bin/moor-rtc`, and add `/etc/systemd/system/moor-relay.service`:

```ini
[Unit]
Description=Moor relay
After=network-online.target

[Service]
ExecStart=/usr/local/bin/moor-rtc relay --listen :80
DynamicUser=yes
AmbientCapabilities=CAP_NET_BIND_SERVICE
Restart=always

[Install]
WantedBy=multi-user.target
```

```sh
sudo systemctl enable --now moor-relay
curl http://RELAY_ADDRESS/     # prints "moor-rtc relay"
```

### 2. Host

Put `moor-rtc` in `~/.local/bin/` too, then:

```sh
moor-rtc init --signal ws://RELAY_ADDRESS/ws   # writes ~/.config/moor/rtc.json (room and key); running it again keeps them
```

Keep it running as a user service, `~/.config/systemd/user/moor-rtc.service`:

```ini
[Unit]
Description=Moor direct connections
After=network-online.target

[Service]
ExecStart=%h/.local/bin/moor-rtc host
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

```sh
systemctl --user enable --now moor-rtc
loginctl enable-linger "$USER"            # keep running when you're logged out
journalctl --user -u moor-rtc -f          # "connected to relay" means it's up
```

### 3. Pair

Run `moor pair` on the host again and scan the code. The link now carries the relay address, room and key, and every connection to this machine tries the direct path first.

The machine address in the pairing (`--host`, `--port`) is the fallback for when no direct path opens, so it has to reach the sshd that `moor-rtc host` connects to (port 22 on the host by default). The code pins that sshd's host key; if the fallback address leads to a different sshd (one you started on port 2222, say), the two paths present different host keys and the app refuses the fallback as a changed host key. When the phone comes in through a forwarded port, pass `--sshd-port` with the host's sshd port.

Pair each host once. A direct-connection machine goes over both the private network and the internet; there is no need for a second entry for the private network.

## Fallback path (optional)

When no direct path exists (both sides behind symmetric NAT, or UDP blocked), the app connects to the machine address from pairing instead. If that address can't be reached from outside either, a reverse tunnel on the relay server can stand in for it, at the cost of routing traffic through that server.

The tunnel puts the host's sshd on the public internet, where anyone can connect and try to log in. Leave it off while direct connections work.

On the relay server:

```sh
sudo useradd -m -s /usr/sbin/nologin moortun
```

Append to `/etc/ssh/sshd_config`:

```
Match User moortun
    AllowTcpForwarding remote
    GatewayPorts clientspecified
    PermitTTY no
    X11Forwarding no
```

On the host, make a key just for this with `ssh-keygen -t ed25519 -f ~/.ssh/moor_tunnel -N ''` and add its public key to `~moortun/.ssh/authorized_keys` on the relay server, allowed to open one port only:

```
restrict,port-forwarding,permitlisten="0.0.0.0:2022" ssh-ed25519 AAAA...
```

Reload sshd and open TCP 2022 in the firewall. Then add a user service `moor-tunnel.service` on the host:

```ini
[Service]
ExecStart=/usr/bin/ssh -NT -i %h/.ssh/moor_tunnel -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -R 0.0.0.0:2022:localhost:22 moortun@RELAY_ADDRESS
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

and pair with that address:

```sh
moor pair --host RELAY_ADDRESS --port 2022 --sshd-port 22
```

`--sshd-port` is the host's local sshd port, which `moor pair` uses to read the host key for the QR code. You still pair with your host: the relay server only forwards TCP, and both the SSH host key and the login key belong to the host.

## Which path is in use

- App: a route tag sits at the right end of the input box: "LAN 105ms" or "WAN 85ms" (direct), "Relay" (no direct path, used the fallback address), "SSH" (no direct connection set up) or "Switching" (resuming on a new path). Tap it for the Route panel: details (including whether a drop resumes), and a choice to pin one route: the private network (LAN, office network, VPN) is often steadier at the office, peer to peer over the internet suits being out. A resumable connection moves to the new route without dropping SSH; when it finds no path in two tries it goes back to the old one. On relay, tapping the ticked route reconnects to try a direct path again.
- Host: `journalctl --user -u moor-rtc` logs `connected, host … <-> srflx …` for each connection, then `rtt N ms` every 30 seconds, and why it closed.
- Phone: `adb logcat -s MoorRtc` shows `route direct …` or `route relay …`.

## Drops and reconnects

- Both sides send a keepalive every 2 seconds. After 4 seconds of silence the path counts as disconnected.
- **A path that dies doesn't end SSH.** The machine keeps its sshd connection, and the phone opens a new path through the relay and resumes the same session: each side says how many bytes it has received, the other sends again from there. SSH, the terminal and port forwards (herdr web ui) only see a pause; nothing is logged in again, replayed or redrawn. Measured on a phone switching between office Wi-Fi and 5G behind a proxy VPN: usually 1-3 seconds, sometimes up to about 10 while the network itself settles.
- The phone looks for a new path when the old one has been disconnected for a second, when ICE declares it failed, and at once when Android reports a network coming or going (also the physical networks under a VPN, which the default-network callback doesn't see). Its first two tries wait 1.5 seconds for an answer, later ones 3.
- The machine keeps a session 10 minutes for its phone, long enough for a locked phone (Android freezes the app soon after the screen goes off) to resume when it's unlocked. The phone gives up after a minute without a path, and the app then reconnects from scratch.
- On the wire: the machine labels its data channel `ssh+r1`; the phone opens a session with a hello carrying a random session id, data goes in messages acknowledged every 64 KB, and up to 4 MB stays unacknowledged before input pauses. An app or machine from before this (0.10.32 and older) is served the old way: a dead path ends the connection and the app reconnects.
- A first connection that gets no answer on any path within 3 seconds is dropped and started again (right after the phone wakes its network is often not ready, and a fresh try usually connects in a second or two); tries that keep failing wait longer, 6 then 10 seconds. A fallback address that failed is not tried again for 5 minutes. A connection through the fallback address doesn't resume.
