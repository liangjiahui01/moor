# 不在主机所在网络时

[English](remote-access.md)

主机在 NAT 后面（公司内网、云上 VPC、家里路由器后面），手机在外面，两边互相看不到对方的地址。本文说明怎么借一台有公网地址的服务器把两边连起来，以及每一部分做什么、能看到什么。

## 三个角色

| 角色 | 在哪运行 | 做什么 |
|---|---|---|
| 中继 `moor-rtc relay` | 一台有公网地址的小服务器 | 每次连接时帮两边交换地址，几条消息、几 KB；不碰 SSH 数据 |
| 主机端 `moor-rtc host` | 你的主机，常驻 | 守在中继上等手机来连，打通后把数据通道接到本机 sshd |
| 手机端 `moor-rtc dial` | 打包在 APK 里，应用自动运行 | 经中继拿到主机地址，打通直连，SSH 跑在上面 |

一次连接的过程：

```
手机 ──hello──▶ 中继 ──▶ 主机
手机 ◀──offer── 中继 ◀── 主机      （主机带上自己的候选地址）
手机 ──answer─▶ 中继 ──▶ 主机      （手机带上自己的候选地址）
手机 ◀════════ 直连（WebRTC 数据通道，里面是 SSH）════════▶ 主机
```

为了少走一趟，主机会提前备好一份 offer 放在中继上，手机来了由中继直接交给它，同时通知主机再备一份；手机的 answer 立即发出，自己的候选地址找到一个补发一个。

两边各自问公共 STUN 服务器「我在公网上是什么地址」，把结果写进 offer 和 answer，再同时向对方发包，在各自的 NAT 上打出一条路（UDP 打洞）。打通后中继就不再参与，终端数据只在手机和主机之间走。

## 安全

- offer 和 answer 用配对时生成的共享密钥签名（HMAC-SHA256），密钥只在主机的 `~/.config/moor/rtc.json` 和手机里，不经过中继。中继伪造不了消息，也冒充不了任何一方。
- SSH 原样跑在数据通道里，仍然端到端加密，手机照常按配对时记下的主机公钥校验主机。中继只看得到会话描述（双方的候选地址），看不到终端内容。
- 主机连中继时出示一个由共享密钥算出的凭证（不含密钥本身），中继把房间绑定到第一个出示凭证的主机。只知道房间号的人冒充不了主机，也挤不掉它。
- 中继不往磁盘写任何东西，重启即清空。

## 搭建

需要：一台有公网 IP 的服务器（1 核 1G 足够，流量很小），放行 TCP 80；主机能访问外网 UDP（大多数网络默认可以）。

### 1. 中继

从 [Releases](https://github.com/liangjiahui01/moor/releases) 下载 `moor-rtc-linux-amd64`（ARM 服务器用 `arm64`），放到 `/usr/local/bin/moor-rtc`，写一个 systemd 服务 `/etc/systemd/system/moor-relay.service`：

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
curl http://中继地址/     # 返回 moor-rtc relay 即正常
```

### 2. 主机

同样下载 `moor-rtc` 放到 `~/.local/bin/`，然后：

```sh
moor-rtc init --signal ws://中继地址/ws   # 生成 ~/.config/moor/rtc.json（房间号和密钥），再次运行会保留原有房间号和密钥
```

常驻运行，写成用户服务 `~/.config/systemd/user/moor-rtc.service`：

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
loginctl enable-linger "$USER"            # 没登录时也运行
journalctl --user -u moor-rtc -f          # 出现 connected to relay 即上线
```

### 3. 配对

在主机上重新运行 `moor pair`，手机扫码。配对链接会带上中继地址、房间号和密钥，之后这台机器的每次连接都先试直连。

配对时的机器地址（`--host`、`--port`）是直连打不通时的备用地址，所以它要指向 `moor-rtc host` 连接的那个 sshd（默认本机 22 端口）。二维码里固定的是这个 sshd 的主机公钥；如果备用地址指向另一个 sshd（比如自己在 2222 端口起的），两条路的主机公钥不一样，走备用地址时应用会报主机公钥变了并拒绝连接。手机经端口转发进来时，用 `--sshd-port` 指明本机 sshd 的端口。

同一台主机只配对一次就够了。直连机器同时能走内网和公网，不用为内网再加一台。

## 备用通道（可选）

直连打不通时（两边都在对称型 NAT 后面，或者网络封了 UDP），应用会改连配对时的机器地址。如果这个地址在外网也连不到，可以在中继服务器上开一条反向隧道当备用地址，代价是数据要绕经中继服务器。

这条隧道会把主机的 sshd 暴露在公网上，任何人都能连上来尝试登录。直连能用时建议不开。

在中继服务器上：

```sh
sudo useradd -m -s /usr/sbin/nologin moortun
```

`/etc/ssh/sshd_config` 末尾加：

```
Match User moortun
    AllowTcpForwarding remote
    GatewayPorts clientspecified
    PermitTTY no
    X11Forwarding no
```

在主机上生成一把专用密钥 `ssh-keygen -t ed25519 -f ~/.ssh/moor_tunnel -N ''`，把公钥加到中继服务器 `~moortun/.ssh/authorized_keys`，只许它开一个端口：

```
restrict,port-forwarding,permitlisten="0.0.0.0:2022" ssh-ed25519 AAAA...
```

重载 sshd，安全组或防火墙放行 TCP 2022。主机上再写一个用户服务 `moor-tunnel.service`：

```ini
[Service]
ExecStart=/usr/bin/ssh -NT -i %h/.ssh/moor_tunnel -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -R 0.0.0.0:2022:localhost:22 moortun@中继地址
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

然后用这个地址配对：

```sh
moor pair --host 中继地址 --port 2022 --sshd-port 22
```

`--sshd-port` 是主机本地 sshd 的端口，`moor pair` 用它读主机公钥写进二维码。配对的仍是你的主机：中继服务器只转发 TCP，SSH 的主机公钥和登录用的密钥都是主机自己的。

## 怎么看连的是哪条路

- 应用：连上时状态显示「已连接 · 直连」或「已连接 · 中转」（走了备用地址）。» 面板的「线路」显示当前是内网还是公网、往返延迟多少，也可以固定只走其中一条：内网（同一局域网、公司网络、VPN）在公司里通常更稳，公网点对点适合在外面用。
- 主机：`journalctl --user -u moor-rtc` 里每次连接一行 `connected, host … <-> srflx …`，之后每 30 秒一行 `rtt N ms`；断开时记录原因。
- 手机：`adb logcat -s MoorRtc` 看 `route direct …` 或 `route relay …`。

## 断线与重连

- 两边每 2 秒互发保活，4 秒收不到算断开，8 秒仍收不到判定这条路失效，应用立即重连。
- 一次尝试 3 秒内没有任何路径应答就放弃，马上重来（手机刚醒时网络常常还没准备好，新的一次通常一两秒就通）；连续失败时等待逐次放宽到 6 秒、10 秒。备用地址连不上时，5 分钟内不再尝试。
- 手机切换网络（WiFi 和移动数据之间）时，应用收到系统通知后直接重连，不等超时。
- 重连要再经中继交换一次地址：网络一变，两边的公网地址都可能变，必须有一个固定的地方碰头。这一步通常一秒内完成。从开始重连到终端可用合计约 2 秒，其余时间花在打通直连和 SSH 登录上。
