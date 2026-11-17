# ArkDroid

在安卓手机上把 **AstrBot + ArkLink/ArkBot** 跑起来。一个 APK，装完点几下就能用。

## 装上之后怎么用

1. 下载 `ArkDroid-*-debug.apk`，允许「安装未知来源应用」，装好打开。
2. 点「**全部下载**」——内置下载会并发测速所有镜像，挑最快的那条下，断点续传 + SHA-256 校验，坏包自动换镜像重下。
3. 点「**装 Termux**」——会用系统安装器装 Termux（需要你点一下「安装」）。
4. Termux 第一次打开请执行一次 `termux-setup-storage`，然后回到 ArkDroid 点「**解压运行环境**」。
   这一步会在 `~/.arkdroid/rootfs` 里铺一个 Ubuntu 用户态（proot），再在里面装 Python、AstrBot 依赖。
   首次大约 5–15 分钟，取决于网速；中断了再点一次会接着装。
5. 点「**启动**」。中继（ArkBridge）在 APK 里跑，AstrBot 在 proot 里跑。

## 界面上有什么

| 区域 | 能干什么 |
| --- | --- |
| 下载 | 全部/单项下载，看实时速度、百分比、选了哪个镜像 |
| 终端 | 输入 `astr status` 之类，看输出；有回显缓冲和日志 |
| AstrBot 面板 | 显示面板地址、账号、密码；一键在应用内 WebView 打开 |
| 中继 | 显示 ArkBridge 连接状态、ArkLink 地址、重连次数 |

`astr` 命令：`start` / `stop` / `status` / `log` / `creds` / `resetpw` / `shell` / `run`。
面板密码在 AstrBot 4.30+ 是哈希存储、读不回明文；界面上有「重置面板密码」按钮，走 `astr resetpw`。

## 已知限制（说清楚，不粉饰）

- **命令输出是跑完才回来**：Termux 的 `RUN_COMMAND` 没有真正的流式管道，ArkDroid 是把输出落文件再回传，所以长命令要等结束才看到结果。
- Termux 必须允许外部调用（`allow-external-apps=true`），ArkDroid 会引导你改。
- proot 有性能损耗，AstrBot 启动比在服务器上慢一点。
- 只支持 `arm64-v8a` 和 `x86_64`（Chaquopy 的 CPython 3.13 没有 32 位轮子）。
- debug 包用公共调试签名，**release 包未签名**，正式分发请自备 keystore。

## 这次发布里验过什么

- 镜像测速 / Range 续传 / 校验失败换源：桌面 10 项
- 本地回环传包（真 curl、2 MB `Expect: 100-continue`、chunked）：9 项
- ArkBridge 中继（含 echo 还原、僵死检测、退避重连）：21 + 9 + 4 项
- `termux/install.sh` 语法自检：`bash -n` 通过，且仓库内固定 LF（CRLF 会让 Termux 报 `bad interpreter`）
