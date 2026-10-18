# Moor

A terminal for your phone that keeps your sessions on your own machine.

Moor connects to a machine you can SSH into, keeps every session in tmux there, and lets you hop between them from Android. Close the app, lose the network, come back tomorrow: the sessions are still running. When Claude Code needs your input or finishes a task, your phone gets a notification.

[中文说明](README.zh-CN.md)

## Features

| | |
|---|---|
| Persistent sessions | Each session is a tmux session on the host, on its own socket, so it never touches your own tmux setup. Name it when you create it, pick what to run (shell, Claude Code, herdr or any command) and where to start. |
| Session list | Sessions are grouped into "Needs you", "Running" and "Idle", with a live preview of the last lines. |
| Notifications | Claude Code "needs your input" and "finished" (with its last reply), plus an optional terminal bell from any program. |
| One-scan pairing | Run `moor pair` on the host and scan the QR code: the app fills in the connection and registers its key in one step. |
| Phone-first keys | One row of keys, swipe up on a key for its second function, a sheet with symbols, Ctrl combos, paste and an input box for dictation. |
| Light and dark | Follows the system, or pick one. |
| CJK aware | Sarasa Term SC keeps Chinese glyphs exactly two columns wide. |

## Requirements

- Android 8.0 or newer.
- A host you can reach over SSH (Linux or macOS) with `tmux`. `python3` is needed for pairing and notifications.
- The phone must be able to reach the host directly: same LAN, VPN, or a public address.

## Get started

1. Install the APK from [Releases](https://github.com/liangjiahui01/moor/releases).
2. On the host, install the helper:

   ```sh
   mkdir -p ~/.local/bin
   curl -fsSL https://raw.githubusercontent.com/liangjiahui01/moor/main/server/moor -o ~/.local/bin/moor
   chmod +x ~/.local/bin/moor
   ```

3. Run `moor pair` and scan the QR code in the app (Settings, Scan QR). Or copy the link it prints and use Paste link.

   ```sh
   moor pair                       # host = first IPv4 address, port 22
   moor pair --host my-box.lan --cmd claude
   ```

Prefer to do it by hand? Fill in host, port and user in Settings, then copy the phone's public key into `~/.ssh/authorized_keys` on the host (Settings, Copy install command).

## Notifications

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
SSH connection ───── attach ───────▶ tmux -L moor  (one tmux session per Moor session)
               ───── list/create ──▶ tmux commands over exec channels
               ───── moor watch ───▶ ~/.local/state/moor/events.log
                                       ▲            ▲
                                       │            │
                       Claude Code hooks      tmux bell hook
                       (moor notify claude)   (moor notify bell)
```

- One SSH connection carries the attached terminal plus short exec channels for listing and creating sessions.
- The app installs the helper to `~/.local/share/moor/bin/moor` and its tmux config to `~/.config/moor/tmux.conf` when it connects.
- Pairing: `moor pair` adds a throwaway key to `authorized_keys` with `expiry-time`, `restrict` and `command=` so it can only run `moor claim`. The QR code carries the host, the host key and the throwaway key's seed. The phone logs in once, hands over its own public key, and the throwaway key is removed. The host key from the QR code is pinned, so there is no trust-on-first-use prompt.
- The private key is generated on the phone and never leaves it.

## Build

```sh
cd android
./scripts/fetch-font.sh          # downloads Sarasa Term SC (needs 7z)
./gradlew assembleDebug
```

Optional `android/defaults.properties` prefills the settings screen:

```properties
host=my-box.lan
user=me
```

Release builds are signed with `android/keystore.properties` when present (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), otherwise with the debug key.

## Roadmap

- iOS app.
- Reaching the host from outside its network without a VPN.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for bundled third-party work, including the modified Termux terminal emulator.
