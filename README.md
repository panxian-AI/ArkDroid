# ArkDroid

把 [ArkBridge](https://github.com/panxian-AI/ArkBridge) 装进 Android：**手机当常驻 OneBot 中继**，
要跑 AstrBot 的话，App 里点一下就能把它托管到手机上。

```
QQ ↔ ArkLink  ↗ ArkBridge（跑在 App 里）↘  AstrBot
              （两头都是 ArkBridge 主动连出去）
```

## 先说清楚一件事：AstrBot 没法塞进 APK

这不是偷懒，是依赖本身决定的：

- AstrBot 依赖 `faiss-cpu`、`pandas`、`watchfiles`、`ormsgpack`、`cryptography`、`pillow` —— 全带原生扩展。
  这些包在 PyPI 上只有 **manylinux（glibc）/ musllinux** 轮子，`faiss-cpu 1.16.0` 的 aarch64 轮子是
  `manylinux_2_28_aarch64`。
- Android 用的是 bionic libc，不是 glibc。Chaquopy、python-for-android 这类「APK 内嵌 CPython」的方案
  只能装纯 Python 包，以及它们自己预编译过的少数库；`faiss-cpu` 这种没有 Android 轮子的，pip 直接失败。
- 社区和官方在安卓上的做法都是 **Termux + proot Ubuntu**：proot 里是一个 glibc 用户态，
  manylinux aarch64 轮子原样能用。这也是本项目对 AstrBot 的处理方式。

所以职责是这么分的：

| 组件 | 放哪 | 为什么 |
|---|---|---|
| ArkBridge 中继 | **APK 内置**（Chaquopy + 内置 `arkbridge`，纯标准库） | 几十 KB，没有原生依赖，跑在 App 进程里最省事 |
| AstrBot | Termux 的 proot Ubuntu | 依赖几百 MB 且都是 glibc 轮子，只能在 Linux 用户态里装 |
| ArkLink / QQ 协议端 | 手机 Termux 或你的电脑/服务器 | 它要 hook QQ 客户端，本身是 Linux 侧的东西 |

一句话：**中继是"App 的一部分"，AstrBot 是"App 托管的外部服务"**，两者都在同一个界面上管。

## 三种用法

| 场景 | 要装什么 | 链路 |
|---|---|---|
| 手机只当中继 | 只装 ArkDroid | QQ ↔ ArkLink（电脑）↔ **ArkDroid** ↔ AstrBot（电脑/服务器） |
| 手机全托管 | ArkDroid + Termux | QQ ↔ ArkLink（Termux）↔ **ArkDroid** ↔ AstrBot（Termux） |
| 已有 ArkBot 全家桶 | ArkBot（`compat` 段） | 不需要 ArkDroid，见 [ArkBot 的 AstrBot 接入文档](https://github.com/panxian-AI/ArkBot/blob/main/docs/guides/AstrBot接入.md) |

第一种是最省电的用法：手机插着电扔抽屉，电脑关机也不影响中继。

## 快速开始

### 1. 装 APK

从 [Releases](https://github.com/panxian-AI/ArkDroid/releases) 下 `app-debug.apk`（debug 包已签名，能直接装，
约 39 MB，因为里面带了 arm64 + x86_64 两套 CPython 3.13 运行时），
或者从 Actions 的 `arkdroid-apk` artifact 拿。要自己签 release 包见文末「构建」。

### 2. 只当中继

打开 App，两块输入框填地址：

- **ArkLink 地址**：`127.0.0.1:6700` 或 `192.168.1.10:6700`（写 `arklink://` 会补 `/onebot/v11/ws`）
- **AstrBot 地址**：`127.0.0.1:6199`（写 `astrbot://` 会补 `/ws`）
- 机器人 QQ 号可以留空，中继会连上 ArkLink 用 `get_login_info` 自己问

点「保存并重启中继」→ 点「启动」。状态条会显示 `中继 · 运行中`，`账号 · 123456`。

### 3. 手机全托管（AstrBot 也装手机上）

前置：

1. 装 **Termux**（推荐 [F-Droid 版](https://f-droid.org/packages/com.termux/)；Play 商店的版本已停更）
2. 在 Termux 里跑一次，让本 App 能发命令：

   ```bash
   echo "allow-external-apps=true" >> ~/.termux/termux.properties
   ```

然后在 ArkDroid 里点 **AstrBot → 安装 / 更新**：脚本会在 Termux 里装 `proot-distro`、建 Ubuntu、
装 Python 3.12 + uv、拉 AstrBot 并 `uv sync`（首次几百 MB，挂 WiFi）。

装完点 **启动**，等状态变成 `AstrBot · 在线`，点 **打开 AstrBot 面板** 就是 `127.0.0.1:6185`。
面板里加一个 `aiocqhttp` 平台，端口填 ArkDroid 里那个（默认 6199），token 两边一致 —— 中继就通了。

## 界面

| 卡片 | 内容 |
|---|---|
| OneBot 中继 | 启动 / 停止 / 自检、事件与 API 计数、开机自启开关、电池优化白名单 |
| 连接 | ArkLink 与 AstrBot 地址、QQ 号、两侧 token；保存即重启中继 |
| AstrBot | Termux 状态、安装/更新、启动、停止、打开面板 |
| 运行日志 | 中继与 Python 侧输出，可滚动、复制、清空 |

顶栏三个小标签是实时状态：`中继`、`账号`（学到的 QQ 号）、`AstrBot`（6185 是否在线）。

「自检」不启动中继，分别探两头：连 ArkLink 问 `get_login_info`，再按 OneBot 实现的身份跟 AstrBot
握一次手（AstrBot 日志里会多一条连接/断开，正常）。结果直接写进日志区。

## Termux 侧的 `astr` 命令

安装脚本会注册一个 `astr`：

| 命令 | 作用 |
|---|---|
| `astr start` | 后台 tmux 会话启动，等到面板能访问才返回 |
| `astr stop` / `astr restart` | 停止 / 重启 |
| `astr status` | 进程 + 面板端口 |
| `astr log` | 进会话看实时输出（`Ctrl+b` `d` 退出，不会停） |
| `astr tail 200` | 看最近 200 行日志文件 |
| `astr shell` | 进 Ubuntu |
| `astr update` | `git pull` + `uv sync` |
| `astr uninstall` | 只移除命令，数据留着 |

手动安装（不走 App）：

```bash
curl -fsSL -o ~/arkdroid-install.sh \
  https://raw.githubusercontent.com/panxian-AI/ArkDroid/main/termux/install.sh
bash ~/arkdroid-install.sh          # 默认国内镜像；加 --no-mirror 关掉
```

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

`arkbridge` 不复制一份进仓库——`tools/sync_arkbridge.py` 从 ArkBridge 仓库取，保证两边不漂移，
`app/src/main/python/ARKBRIDGE_VERSION` 记录同步到哪个 ref。

## 测试

```bash
python tools/selftest_python.py      # 13 项：配置→启动→状态→日志→停止→自检（桌面，不碰 Android）
```

它验证的是 App 里那层 Python 胶水（`arkdroid.py` + 内置 `arkbridge`）：导入路径、TOML 字段、
状态 JSON 形状、线程与事件循环、坏配置是否有话好好说。

**没有做的**：真机 UI/服务生命周期的自动化测试。这个仓库里我能保证的是「能编译出 APK」+
「Python 层逻辑正确」，装到手机上的实际表现需要你验证。

## 权限都用来干什么

| 权限 | 用途 |
|---|---|
| `INTERNET` | 连 ArkLink / AstrBot，都是你填的地址 |
| `FOREGROUND_SERVICE` + `POST_NOTIFICATIONS` | 中继常驻，通知栏显示状态 |
| `WAKE_LOCK` | 息屏时不至于立刻被挂起 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启（可关） |
| `com.termux.permission.RUN_COMMAND` | 往 Termux 发 `astr` / 安装命令 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请电池白名单，别被系统杀 |

没有任何统计、上报、云服务；`usesCleartextTraffic` 是为了本机 `ws://` 与 6185 面板。

## 已知限制

- **只支持 arm64 与 x86_64**：Chaquopy 的 Python 3.13 没有 32 位 ARM 构建，armeabi-v7a 的老机器装不上
- **AstrBot 首次安装 2.5 GB 左右**（Ubuntu rootfs + 依赖），低存储机器会失败
- 中继断线那段时间的消息补不回来（ArkLink 只推给在线客户端）；缓冲只覆盖「AstrBot 晚连上」这一段
- 多账号：在 App 里只能配一条链路；要多条就装 ArkBridge 本体用多段 `[[bridge]]`
- Termux 若被系统冻结（部分国产 ROM），中继仍在跑但 AstrBot 会掉线，需要给 Termux 也加白名单

## 相关

- [ArkBridge](https://github.com/panxian-AI/ArkBridge) —— 中继本体（Python，零依赖，CLI + 库）
- [ArkLink](https://github.com/panxian-AI/ArkLink) —— OneBot 11 协议端
- [ArkBot](https://github.com/panxian-AI/ArkBot) —— 带插件体系与 OneBot 兼容层的机器人框架

MIT License。
