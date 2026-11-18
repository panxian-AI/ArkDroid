# ArkDroid

把 [ArkBridge](https://github.com/panxian-AI/ArkBridge) 装进 Android：**手机当常驻 OneBot 中继**，
要跑 AstrBot 的话，App 里点一下就能把它装到手机上，并在同一个界面里管起来 ——
下载、终端、面板账号、内置浏览器，都不用手打命令。

```
QQ ↔ ArkLink  ↗ ArkBridge（跑在 App 里）↘  AstrBot（proot Ubuntu）
              （两头都是 ArkBridge 主动连出去）
```

## 先说清楚一件事：AstrBot 没法塞进 APK

这不是偷懒，是依赖本身决定的：

- AstrBot 依赖 `faiss-cpu`、`pandas`、`watchfiles`、`ormsgpack`、`cryptography`、`pillow` —— 全带原生扩展。
  这些包在 PyPI 上只有 **manylinux（glibc）/ musllinux** 轮子，`faiss-cpu 1.16.0` 的 aarch64 轮子是
  `manylinux_2_28_aarch64`。
- Android 用的是 bionic libc，不是 glibc。Chaquopy、python-for-android 这类「APK 内嵌 CPython」的方案
  只能装纯 Python 包；`faiss-cpu` 这种没有 Android 轮子的，pip 直接失败。
- 社区和官方在安卓上的做法都是 **proot + glibc 用户态**：manylinux aarch64 轮子原样能用。

所以职责是这么分的：

| 组件 | 放哪 | 为什么 |
|---|---|---|
| ArkBridge 中继 | **APK 内置**（Chaquopy + 内置 `arkbridge`，纯标准库） | 几十 KB，没有原生依赖，跑在 App 进程里最省事 |
| AstrBot | proot 的 Ubuntu 根文件系统 | 依赖几百 MB 且都是 glibc 轮子，只能在 Linux 用户态里装 |
| Linux 环境本体 | Termux（App 能一键下载并拉起安装器） | Android 不允许 App 自己带一个 Linux 内核，Termux 提供 `proot` 与终端 |

一句话：**中继是"App 的一部分"，AstrBot 是"App 托管的外部服务"**，两者都在同一个界面上管。

## 内置下载：点一下就行

App 里有一张「内置下载」卡片，点 **一键下载全部**：

- App **同时向几个镜像要 256 KB** 测速，按实测速度排序，从最快的那个开始下；
- 中途断了用 HTTP Range **续传**，换镜像也能接着传；
- 下完按清单里的 **SHA-256 校验**，镜像给了旧包/坏包会被丢掉并换下一个；
- 镜像清单在 [`dist/manifest.json`](dist/manifest.json)，App 启动时会尝试拉线上版本 ——
  **换镜像不用重发 APK**。

下的是这三样东西（都不大，合计约 75 MB）：

| 组件 | 大小 | 镜像 |
|---|---|---|
| Termux APK | 35 MB | GitHub Releases（走 `ghfast.top` / `gh-proxy.com` 加速） |
| Ubuntu 24.04 base rootfs | 30 MB | 清华 / 阿里云 / 中科大 / 官方 cdimage |
| AstrBot 源码 | 9 MB | codeload（同样走加速镜像） |

下好之后 **安装不再走流量**：App 在 `127.0.0.1` 起一个带随机 token 的小文件服务，
Termux 用 `curl` 从本机把包取走（Termux 读不到 App 私有目录，但能连回环）。
这个服务只绑回环地址，外网连不上，路径里的 token 别的 App 也猜不到。

## 三种用法

| 场景 | 要装什么 | 链路 |
|---|---|---|
| 手机只当中继 | 只装 ArkDroid | QQ ↔ ArkLink（电脑）↔ **ArkDroid** ↔ AstrBot（电脑/服务器） |
| 手机全托管 | ArkDroid + Termux（App 能下载） | QQ ↔ ArkLink（Termux）↔ **ArkDroid** ↔ AstrBot（proot） |
| 已有 ArkBot 全家桶 | ArkBot（`compat` 段） | 不需要 ArkDroid，见 [ArkBot 的 AstrBot 接入文档](https://github.com/panxian-AI/ArkBot/blob/main/docs/guides/AstrBot接入.md) |

第一种是最省电的用法：手机插着电扔抽屉，电脑关机也不影响中继。

## 快速开始

### 1. 装 APK

从 [Releases](https://github.com/panxian-AI/ArkDroid/releases) 下 `arkdroid-*-debug.apk`
（debug 包已签名，能直接装，约 39 MB —— 里面带了 arm64 + x86_64 两套 CPython 3.13 运行时），
或者从 Actions 的 `arkdroid-apk` artifact 拿。要自己签 release 包见文末「构建」。

### 2. 只当中继

打开 App，两块输入框填地址：

- **ArkLink 地址**：`127.0.0.1:6700` 或 `192.168.1.10:6700`（写 `arklink://` 会补 `/onebot/v11/ws`）
- **AstrBot 地址**：`127.0.0.1:6199`（写 `astrbot://` 会补 `/ws`）
- 机器人 QQ 号可以留空，中继会连上 ArkLink 用 `get_login_info` 自己问

点「保存并重启中继」→ 点「启动」。状态条会显示 `中继 · 运行中`、`账号 · 123456`。

### 3. 手机全托管（AstrBot 也装手机上）

1. **内置下载 → 一键下载全部**（约 75 MB，进度和速度都显示在卡片上）
2. **ArkTerm 没装** 时点「安装 / 更新」，会直接用下好的 APK 拉起系统安装器。
   ArkTerm 是我们重打包的 Termux，外部应用授权（`allow-external-apps`）已内置，
   **装上即用，不用敲任何配置命令**。之前装过官方 Termux 的需要先卸载（包名相同）。
3. 点 **AstrBot → 安装 / 更新**：解 rootfs → 装 Python 3.12 + uv → 解 AstrBot → `uv sync`
   （首次约 1 GB 依赖，挂 WiFi。Termux 窗口里能看到实时进度，装完 App 里显示完整日志）
4. 装完点 **启动**，等标签变成 `AstrBot · 在线`
5. 点 **读取账号** 看面板用户名/密码，点 **内置浏览器** 直接进 `127.0.0.1:6185`

面板里加一个 `aiocqhttp` 平台，端口填 ArkDroid 里那个（默认 6199），token 两边一致 —— 中继就通了。

## 界面

| 卡片 | 内容 |
|---|---|
| 状态标签 | `中继` / `账号`（学到的 QQ 号）/ `AstrBot`（6185 是否在线） |
| OneBot 中继 | 启动 / 停止 / 自检、事件与 API 计数、开机自启、电池白名单 |
| 连接 | ArkLink 与 AstrBot 地址、QQ 号、两侧 token；保存即重启中继 |
| **内置下载** | 一键下载全部 / 单项下载、进度条、当前速度与所选镜像 |
| **AstrBot** | 安装 / 更新、启动、停止、**读取账号**、**重置面板密码**、**内置浏览器** |
| **终端** | Termux 里执行命令，输出回到 App；`状态` / `日志` / `清空` 快捷按钮 |
| 运行日志 | 中继与 Python 侧输出，可滚动、复制、清空 |

### 账号密码

AstrBot 的面板账号存在 `data/cmd_config.json` 的 `dashboard` 段里，「读取账号」就是让 Termux 读它
（`astr creds`）：

- **用户名**：默认 `astrbot`
- **密码**：AstrBot 4.30 起改成 pbkdf2 哈希存储，**读不回明文**，App 会明确写「已加密保存」；
  还没设过时提示「第一次打开面板会让你设一个」（新版本的默认行为，不再是 `astrbot/astrbot`）
- **忘了密码**：点「重置面板密码」，走 AstrBot 认的 `ASTRBOT_RESET_DASHBOARD_PASSWORD` 环境变量重启一次，
  新密码会被写进配置

## Termux 侧的 `astr` 命令

安装脚本会注册 `astr`（也注册到 `$PREFIX/bin`，Termux 里直接能用）：

| 命令 | 作用 |
|---|---|
| `astr start` / `stop` / `restart` | 起停 AstrBot（nohup + pid 文件，日志在 `~/.arkdroid/astr.log`） |
| `astr status` | 进程 + 面板端口 HTTP 码 |
| `astr log` | 看日志尾部 |
| `astr creds` | 打印 `USER/PASS/HASHED/FIRST_RUN`，App 的「读取账号」用它 |
| `astr resetpw 新密码` | 用环境变量重置面板密码 |
| `astr shell` | 进 proot Ubuntu |
| `astr run <命令>` | 在 Ubuntu 里执行一条命令 |

手动安装（不用 App，需要能连 GitHub）：

```bash
curl -fsSL -o ~/arkdroid-install.sh \
  https://raw.githubusercontent.com/panxian-AI/ArkDroid/main/termux/install.sh
bash ~/arkdroid-install.sh          # 默认国内镜像；加 --no-mirror 关掉
```

脚本只依赖 Termux 的 `proot`，**不再需要 proot-distro**：rootfs 就是一个 Ubuntu base 包，
解到 `~/.arkdroid/rootfs` 再用 `proot` 进去，路径和参数都在脚本里，没有额外一层抽象。

## 构建

需要 JDK 21、Android SDK 35。

```bash
python tools/sync_arkbridge.py       # 把 ArkBridge 的 arkbridge 包同步进 app/src/main/python
ARKDROID_BUILD_PYTHON=python ./gradlew assembleDebug
```

- `ARKDROID_BUILD_PYTHON`：Chaquopy 需要一个本机 CPython 跑构建期的 pip，指到你的 `python`/`python3`
- 打 release 包时给 `ARKDROID_KEYSTORE` / `ARKDROID_STORE_PASSWORD` / `ARKDROID_KEY_ALIAS` /
  `ARKDROID_KEY_PASSWORD`，没给就是未签名包
- CI（`.github/workflows/android.yml`）在 push 与打 tag 时构建 debug + release，tag 会自动发 Release

`arkbridge` 不复制一份进仓库 —— `tools/sync_arkbridge.py` 从 ArkBridge 仓库取，保证两边不漂移。
`dist/manifest.json` 与 `termux/` 是唯一来源，构建时由 Gradle 同步进 `assets/`。

## 测试

```bash
python tools/selftest_downloader.py   # 10 项：镜像测速择优、Range 续传、校验失败换镜像
python tools/selftest_http.py         #  9 项：真 curl 打上来（管道/文件/2MB Expect）+ chunked 解析
python tools/selftest_python.py       # 13 项：配置→启动→状态→日志→停止→自检
```

前两个是这一版新增的重点：下载内核和 HTTP 解析都**不依赖 Android**，所以能在桌面上用真 Java、
真 curl、真 HTTP 服务跑；跑不过就不出包。

**没有做的**：真机 UI/WebView/服务生命周期的自动化测试。这个仓库能保证的是「能编译出 APK」+
「下载与回传协议在桌面上验过」，装到手机上的实际表现需要你验证。

## 权限都用来干什么

| 权限 | 用途 |
|---|---|
| `INTERNET` | 连 ArkLink / AstrBot（你填的地址）+ 下载组件 |
| `FOREGROUND_SERVICE` + `POST_NOTIFICATIONS` | 中继常驻；安装期间保活，本地文件服务不掉 |
| `WAKE_LOCK` | 息屏时不至于立刻被挂起 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启（可关） |
| `com.termux.permission.RUN_COMMAND` | 往 Termux 发 `astr` / 安装命令 |
| `REQUEST_INSTALL_PACKAGES` | 装自己下好的 Termux APK |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请电池白名单，别被系统杀 |

没有统计、上报、云服务；`usesCleartextTraffic` 是为了本机 `ws://`、6185 面板和 `127.0.0.1` 取包。

## 已知限制

- **只支持 arm64 与 x86_64**：Chaquopy 的 Python 3.13 没有 32 位 ARM 构建，armeabi-v7a 的老机器装不上
- **AstrBot 首次安装约 2.5 GB**（rootfs + Ubuntu 里的包 + 依赖），低存储机器会失败
- **命令输出是"跑完才回来"**：curl 会把 stdin 读完再发请求，所以不做逐行流式；安装过程的实时进度看 Termux 窗口，App 里点「日志」能随时抓一段
- 中继断线那段时间的消息补不回来（ArkLink 只推给在线客户端）；缓冲只覆盖「AstrBot 晚连上」这一段
- 多账号：在 App 里只能配一条链路；要多条就装 ArkBridge 本体用多段 `[[bridge]]`
- Termux 若被系统冻结（部分国产 ROM），中继仍在跑但 AstrBot 会掉线，需要给 Termux 也加白名单

## 相关

- [ArkBridge](https://github.com/panxian-AI/ArkBridge) —— 中继本体（Python，零依赖，CLI + 库）
- [ArkLink](https://github.com/panxian-AI/ArkLink) —— OneBot 11 协议端
- [ArkBot](https://github.com/panxian-AI/ArkBot) —— 带插件体系与 OneBot 兼容层的机器人框架

MIT License。
