# Moor

A terminal for your phone that keeps your sessions on your own machine.

Moor connects to a machine you can SSH into, keeps every session running there, and lets you hop between them from Android. Close the app, lose the network, come back tomorrow: the sessions are still running. When Claude Code needs your input or finishes a task, your phone gets a notification.

[中文说明](README.zh-CN.md)

<p align="center">
  <img src="docs/screenshots/list-light.png" width="200" alt="Session list">
  <img src="docs/screenshots/terminal-dark.png" width="200" alt="Terminal">
  <img src="docs/screenshots/new-light.png" width="200" alt="New session">
  <img src="docs/screenshots/keys-dark.png" width="200" alt="Keys sheet">
</p>

## Features

| | |
|---|---|
| Persistent sessions | A small session daemon on the host keeps each session's terminal, screen and last 5,000 lines. When you come back you get the screen exactly as it is, and you scroll the history right on the phone. Name it when you create it, pick what to run (shell, Claude Code, herdr or any command) and where to start. |
| Session list | Sessions are grouped into "Needs you", "Running" and "Idle", with a live preview of the last lines. |
| Several machines | Add as many machines as you like and switch between them from the top of the session list. Each one remembers its last session. |
| Notifications | Claude Code "needs your input" and "finished" (with its last reply), plus an optional terminal bell from any program. |
| One-scan pairing | Run `moor pair` on the host and scan the QR code: the app fills in the connection and registers its key in one step. To add a tablet or a second phone, use Settings, Set up another device on the first one: step 1 shows a code the new device's camera scans to download Moor from GitHub, step 2 a code to scan in Moor, which brings over every machine and the settings while the new device registers its own key. |
| Voice input | Tap the mic beside the input box and talk: the text appears in the box as you speak, ready to edit and send, without opening the keyboard. Uses Volcengine (Doubao) streaming speech recognition with your own key. |
| Images | Send a photo or screenshot to the session (photos, camera, or share from another app). It is copied to the machine and its path typed in, which Claude Code reads as an image. |
| Phone-first input | Two rows at the bottom: named keys that scroll sideways (Paste, Esc, Tab, Enter, Shift+Tab, arrows, Ctrl, Alt, …), and an input box where you type or dictate a whole line with the normal keyboard, then send it. A sheet holds symbols and Ctrl combos; the keyboard key types straight into the terminal. The title bar is hidden by default (turn it on in the sheet); the first key opens the session list. |
| Light and dark | Follows the system, or pick one. |
| Ghostty look | Catppuccin Mocha / Latte colors, JetBrains Mono with symbol and Nerd Font icons, bar cursor; Chinese glyphs sit centred in two columns. |

## Requirements

- Android 8.0 or newer.
- A host you can reach over SSH (Linux or macOS) with `python3`. Nothing else to install: the app copies its helper over when it connects.
- The phone must reach the host's SSH port (same LAN, VPN, a public address, or a forwarded port), or use [direct connections](#away-from-the-hosts-network) through a small relay.

## Get started

1. Install the APK from [Releases](https://github.com/liangjiahui01/moor/releases). The app checks there for a new version once a day and offers it; Settings, Check for updates checks right away.
2. On the host, install the helper:

   ```sh
   mkdir -p ~/.local/bin
   curl -fsSL https://raw.githubusercontent.com/liangjiahui01/moor/main/server/moor -o ~/.local/bin/moor
   chmod +x ~/.local/bin/moor
   ```

3. Run `moor pair` and scan the QR code in the app (Settings, Scan QR). Or copy the link it prints and use Paste link.

   ```sh
   moor pair                       # host = first IPv4 address, port 22
   moor pair --host my-box.lan --cmd claude --name my-box
   ```

To add another machine later, run `moor pair` on it and use Add a machine at the top of the session list. Scanning a machine you already have updates it.

Prefer to do it by hand? Fill in host, port and user in Settings, then copy the phone's public key into `~/.ssh/authorized_keys` on the host (Settings, Copy install command).

## Away from the host's network

When the host sits behind NAT (an office or cloud network) and the phone is elsewhere, Moor can connect the two directly over WebRTC. A small relay you run on a public server lets them swap addresses for each connection; after that SSH flows peer to peer, and the relay never sees it.

1. On the public server, run `moor-rtc relay --listen :80`.
2. On the host, run `moor-rtc init --signal ws://RELAY_ADDRESS/ws`, then keep `moor-rtc host` running.
3. Pair again with `moor pair`.

The full guide (systemd units, a fallback tunnel for when no direct path exists, security, and how to tell which path is in use) is in [docs/remote-access.md](docs/remote-access.md).

## Notifications

<p align="center"><img src="docs/screenshots/notification.png" width="420" alt="Task finished notification"></p>

| Event | Source | Default |
|---|---|---|
| Needs input | Claude Code `Notification` hook | On |
| Task finished | Claude Code `Stop` hook, with the last assistant reply | On |
| Terminal bell | Any program in a Moor session rings the bell | Off |

Turn on the Claude Code hooks in Settings, or on the host:

```sh
moor hooks install     # adds two hooks to ~/.claude/settings.json, keeps yours, saves a backup
moor hooks status
moor hooks uninstall
```

Hooks fire wherever Claude Code runs, not only inside Moor sessions. Events from inside herdr are labeled with the herdr tab name. While any notification type is on, the app stays connected in the background (an ongoing notification shows the state).

Tapping a notification opens that session. You don't get notified about the session you are looking at.

## How it works

```
phone (Moor)                         host
────────────                         ────
SSH connection ───── attach ───────▶ moor attach ──▶ session daemon (owns every session's terminal)
               ───── list/create ──▶ moor ls / new / kill over exec channels
               ───── moor watch ───▶ ~/.local/state/moor/events.log
                                       ▲            ▲
                                       │            │
                       Claude Code hooks      bell in a session
                       (moor notify claude)   (daemon)          
```

- One SSH connection to the current machine carries the attached terminal plus short exec channels for listing and creating sessions. Notifications come from the current machine.
- The app installs the helper to `~/.local/share/moor/bin/moor` when it connects. The helper starts the session daemon on demand. The daemon runs each session's output through a terminal emulator, so attaching sends a snapshot: the history, the screen, the cursor and terminal modes (alternate screen, mouse, bracketed paste).
- When the helper is updated, the running daemon swaps in the new code without stopping any session.
- Images go to `~/.cache/moor/uploads` on the machine; files older than a week are removed.
- Pairing: `moor pair` adds a throwaway key to `authorized_keys` with `expiry-time`, `restrict` and `command=` so it can only run `moor claim`. The QR code carries the host, the host key and the throwaway key's seed. The phone logs in once, hands over its own public key, and the throwaway key is removed. The host key from the QR code is pinned, so there is no trust-on-first-use prompt.
- Setting up another device: the configured device logs in to each machine with its own key and runs `moor invite`, which adds the same kind of throwaway key (made on the device). The QR code carries one such pairing link per machine plus the app settings, deflated; the new device claims each link with its own key. No private key is copied.
- The private key is generated on the phone and never leaves it.
- Direct connections: for each SSH connection the app runs `moor-rtc dial` (bundled in the APK) and speaks SSH over its stdin and stdout. It sends an offer through the relay to `moor-rtc host`, which answers; the two then find a path with ICE (public STUN servers, no TURN) and open a data channel that the host connects to its sshd. Offers and answers are signed with the shared key from the pairing link, which never crosses the network. SSH runs inside unchanged, so it stays end-to-end encrypted and the host key is checked as before; the relay sees only the session descriptions.

## Build

```sh
cd android
./gradlew assembleDebug
```

Optional `android/defaults.properties` prefills the settings screen:

```properties
host=my-box.lan
user=me
```

Go (1.22 or newer) builds `moor-rtc` into the APK for direct connections; without Go the app builds without them.

Release builds are signed with `android/keystore.properties` when present (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), otherwise with the debug key.

## Roadmap

- iOS app.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for bundled third-party work, including the modified Termux terminal emulator.
