# Moor

手机上的终端，会话留在你自己的机器上。

Moor 通过 SSH 连到你的机器，每个会话都放在那台机器的 tmux 里，手机上随时切换。关掉应用、断网、隔天再打开，会话都还在跑。Claude Code 等你输入或者跑完任务时，手机收到通知。

[English](README.md)

## 功能

| | |
|---|---|
| 持久会话 | 每个会话是主机上的一个 tmux 会话，用独立的 socket，不碰你自己的 tmux 配置。新建时起名，选运行什么（shell、Claude Code、herdr 或任意命令）和起始目录。 |
| 会话列表 | 按「需要你处理」「运行中」「空闲」分组，带最后几行输出的预览。 |
| 通知 | Claude Code 等你输入、任务完成（带它最后一句回复），可选任意程序的终端响铃。 |
| 扫码配对 | 主机上运行 `moor pair`，手机扫码，连接信息和公钥一步配好。 |
| 为手机设计的按键 | 一行按键，按键上滑出第二功能；面板里有符号、Ctrl 组合、粘贴和适合语音输入的输入框。 |
| 浅色和深色 | 跟随系统，或手动选。 |
| 中文对齐 | 用更纱 Term SC，中文正好占两格。 |

## 要求

- Android 8.0 及以上。
- 一台能 SSH 登录的主机（Linux 或 macOS），装有 `tmux`；配对和通知需要 `python3`。
- 手机要能直连主机：同一局域网、VPN 或公网地址。

## 开始使用

1. 从 [Releases](https://github.com/liangjiahui01/moor/releases) 下载安装 APK。
2. 在主机上安装辅助脚本：

   ```sh
   mkdir -p ~/.local/bin
   curl -fsSL https://raw.githubusercontent.com/liangjiahui01/moor/main/server/moor -o ~/.local/bin/moor
   chmod +x ~/.local/bin/moor
   ```

3. 运行 `moor pair`，在应用里「设置 → 扫码配对」。也可以复制它打印的链接，用「粘贴链接」。

   ```sh
   moor pair                       # 主机地址取第一个 IPv4，端口 22
   moor pair --host my-box.lan --cmd claude
   ```

想手动配置：在设置里填主机、端口、用户名，再把手机公钥加到主机的 `~/.ssh/authorized_keys`（设置 → 复制添加命令）。

## 通知

| 事件 | 来源 | 默认 |
|---|---|---|
| 等你处理 | Claude Code 的 `Notification` 钩子 | 开 |
| 任务完成 | Claude Code 的 `Stop` 钩子，带最后一句回复 | 开 |
| 终端响铃 | Moor 会话里任意程序响铃 | 关 |

在设置里打开 Claude Code 钩子，或在主机上运行：

```sh
moor hooks install     # 在 ~/.claude/settings.json 加两个钩子，保留已有钩子，并备份原文件
moor hooks status
moor hooks uninstall
```

钩子在 Claude Code 运行的任何地方都会触发，不限于 Moor 会话；herdr 里的事件用 herdr 的 tab 名标注。任一通知打开时，应用在后台保持连接（常驻通知显示连接状态）。

点通知直接打开对应会话。你正在看的会话不会再弹通知。

## 工作方式

```
手机（Moor）                          主机
────────────                         ────
SSH 连接 ─────── 接入 ─────────────▶ tmux -L moor（每个 Moor 会话一个 tmux 会话）
         ─────── 列出/新建 ────────▶ 经执行通道跑 tmux 命令
         ─────── moor watch ───────▶ ~/.local/state/moor/events.log
                                       ▲            ▲
                                       │            │
                       Claude Code 钩子       tmux 响铃钩子
                       (moor notify claude)   (moor notify bell)
```

- 一条 SSH 连接，承载当前接入的终端，以及列出、新建会话用的短执行通道。
- 应用连上后把辅助脚本装到 `~/.local/share/moor/bin/moor`，tmux 配置写到 `~/.config/moor/tmux.conf`。
- 配对：`moor pair` 往 `authorized_keys` 加一把临时密钥，带 `expiry-time`、`restrict` 和 `command=`，只能执行 `moor claim`。二维码里有主机地址、主机公钥和临时密钥的种子。手机用它登录一次，交出自己的公钥，临时密钥随即删除。主机公钥按二维码里的固定下来，不走首次连接信任。
- 私钥在手机上生成，不离开手机。

## 构建

```sh
cd android
./scripts/fetch-font.sh          # 下载更纱 Term SC（需要 7z）
./gradlew assembleDebug
```

可选的 `android/defaults.properties` 用来预填设置页：

```properties
host=my-box.lan
user=me
```

存在 `android/keystore.properties`（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）时，release 包用它签名，否则用 debug 证书。

## 规划

- iOS 应用。
- 不靠 VPN 从外网连到主机。

## 许可

Apache License 2.0。随附的第三方作品（含修改过的 Termux 终端模拟器）见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。
