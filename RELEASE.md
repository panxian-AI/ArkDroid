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
| 本机 QQ | 手机自己当 QQ 协议端（见下） |
| 中继 | 显示 ArkBridge 连接状态、ArkLink 地址、重连次数 |

`astr` 命令：`start` / `stop` / `status` / `log` / `creds` / `resetpw` / `shell` / `run`。
另有 `qq`（本机 QQ 无头启动）与 `arklink`（翻译层）两条命令，参数都是 `start / stop / status / log`。
面板密码在 AstrBot 4.30+ 是哈希存储、读不回明文；界面上有「重置面板密码」按钮，走 `astr resetpw`。

## 本机 QQ 模式（0.3.0 起）—— 一台手机就够，不用电脑

完整链路，全程 127.0.0.1，经过 ArkLink：

```
QQ arm64(proot, Xvfb 无头) → NapCat(Milky 3000) → ArkLink(OneBot 6700) → ArkBridge → AstrBot(6199)
```

操作顺序：

1. 下载卡里把「**QQ arm64 安装包**」「**NapCat 压缩包**」「**ArkLink 源码**」下了（或一键全部下载）。
2. 「本机 QQ」卡 → 「**装 NapCat**」= `bash install.sh --qq-only`：apt 装 QQ arm64、把 NapCat.Shell 注入
   QQ、部署 ArkLink。已装好的部分自动跳过。
3. 点「**启动**」（= `qq start; arklink start`），QQ 在 Xvfb 无头环境里起来。
4. 点「**扫码登录**」打开 NapCat WebUI（`127.0.0.1:6099/webui`）登录 QQ，然后在「网络配置」里
   新建一个 **Milky 服务器**，监听 `127.0.0.1:3000`（登录和 Milky 都只配这一次）。
5. 点「**填本机地址**」，中继改成连 `ws://127.0.0.1:6700/onebot/v11/ws`（ArkLink 的 OneBot 11 端口）。

包的来源都是固定 URL + 校验，不赌上游会变的地址：

- QQ arm64：[ArkLink 的 qq-3.2.34-arm64](https://github.com/panxian-AI/ArkLink/releases/tag/qq-3.2.34-arm64)，
  SHA-256 `181b895c…85fdca`。注意 ArkLink 里那个 `qq-3.2.28` 是 **amd64**，只能给电脑用。
- NapCat：官方 GitHub Releases 已清空，改用自建固定位
  [ArkLink 的 napcat-shell](https://github.com/panxian-AI/ArkLink/releases/tag/napcat-shell)（`NapCat.Shell.zip`）。
- ArkLink 源码：`codeload.github.com/panxian-AI/ArkLink`（含国内镜像前缀）。

## 已知限制（说清楚，不粉饰）

- **命令输出是跑完才回来**：Termux 的 `RUN_COMMAND` 没有真正的流式管道，ArkDroid 是把输出落文件再回传，所以长命令要等结束才看到结果。
- Termux 需要允许外部调用。推荐直接用 App 下载卡里的 **ArkTerm**（重打包的 Termux，`allow-external-apps` 已内置，装上即用）；用官方 Termux 的话要手动改 `termux.properties`。
- proot 有性能损耗，AstrBot 启动比在服务器上慢一点；QQ 无头是 Electron，**内存建议 6 GB 以上的手机**。
- NapCat 首次登录要扫码（在 WebUI 里），Milky 服务器要在 WebUI 手动新建一次。
- 只支持 `arm64-v8a` 和 `x86_64`（Chaquopy 的 CPython 3.13 没有 32 位轮子）。
- debug 包用公共调试签名，**release 包未签名**，正式分发请自备 keystore。

## 这次发布里验过什么

- 镜像测速 / Range 续传 / 校验失败换源：桌面 10 项
- 本地回环传包（真 curl、2 MB `Expect: 100-continue`、chunked）：9 项
- ArkBridge 中继（含 echo 还原、僵死检测、退避重连）：21 + 9 + 4 项
- NapCat 注入逻辑：两种 zip 布局 + 幂等重跑，桌面验证通过
- `termux/install.sh` 语法自检：`bash -n` 通过（含全部 `bash -c` 内层块），仓库内固定 LF
  （CRLF 会让 Termux 报 `bad interpreter`）
- **未验**：QQ 无头 + NapCat 注入 + Milky 链路没有真机跑过，第一次装大概率要照报错调一轮
