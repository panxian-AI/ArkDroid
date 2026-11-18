#!/data/data/com.termux/files/usr/bin/bash
# ArkDroid 的 Termux 侧安装器（v2，2026-11 重新设计）。
#
# App 在点「安装 / 更新」时会把本目录下的所有脚本预置到 ~/.arkdroid/，
# 然后用 nohup 后台启动本脚本——安装和 App 的会话完全解耦，App 被杀也照跑。
#
# App 递过来的安装包（http://127.0.0.1:<port>/t/<token>/f/...）：
#   ubuntu-rootfs.tar.gz   Ubuntu 24.04 base（约 30 MB）
#   astrbot.tar.gz         AstrBot 源码（约 9 MB）
#   linuxqq_arm64.deb      QQ NT arm64（约 208 MB，本机 QQ 用）
#   napcat.zip             NapCat.Shell（本机 QQ 用）
#   arklink.tar.gz         ArkLink 源码（本机 QQ 链路）
# 所以这个脚本在断网、GitHub 被墙的情况下也能装完。
#
# 设计要点：
#   1. 所有输出（含报错）经 upload_lines 实时写 ~/.arkdroid/install.log 并 POST 回 App
#   2. 状态写 ~/.arkdroid/state（status/step/desc/err），astr status 随时可查
#   3. astr/qq/arklink 命令在最前面注册，装没装完 astr status 都能用
#   4. 失败不静默：state 里记 status=failed + 原因，进度条和 astr status 都能看到
#   5. 安装是幂等的：重跑会复用已有的 rootfs / AstrBot / QQ，从断掉的地方接着装
#
# 手动跑也可以（没有 App 递的包就走镜像自己下）：
#   bash install.sh              # 全量：AstrBot + 本机 QQ + ArkLink
#   bash install.sh --no-qq      # 只装 AstrBot（协议端在别处）
#   bash install.sh --qq-only    # 只装/补 本机 QQ + ArkLink（AstrBot 已装好）
#   bash install.sh --no-mirror
set -euo pipefail

LOCAL="${ARKDROID_LOCAL:-}"
STATE="$HOME/.arkdroid"
ROOTFS="$STATE/rootfs"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
LOGF="$STATE/install.log"
ARKNET="$STATE/arknet.sh"
USE_MIRROR=1
SKIP_ASTR=0
SKIP_QQ=0
PY_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
ASTRBOT_REPO="https://github.com/AstrBotDevs/AstrBot.git"
RAW_BASE="https://raw.githubusercontent.com/panxian-AI/ArkDroid/main/termux"

for arg in "$@"; do
  case "$arg" in
    --cn|--mirror) USE_MIRROR=1 ;;
    --no-mirror) USE_MIRROR=0 ;;
    --no-qq) SKIP_QQ=1 ;;
    --qq-only) SKIP_ASTR=1 ;;
    -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

# ------------------------------------------------------------------ 回传
# http METHOD URL [body]；GET 的响应打到 stdout（调用处 > 重定向）
# curl 是第 1 步才装的，之前全靠 arknet.sh（bash /dev/tcp）兜底
http() {
  if command -v curl >/dev/null 2>&1; then
    case "$1" in
      POST) curl -sS -m 15 -X POST --data-binary "${3:-$(cat)}" "$2" 2>/dev/null || true ;;
      *)    curl -fsSL -m 1800 "$2" ;;
    esac
  else
    if [ -f "$ARKNET" ]; then
      case "$1" in
        POST) bash "$ARKNET" "$@" >/dev/null 2>&1 || true ;;
        *)    bash "$ARKNET" "$@" ;;
      esac
    else
      [ "$1" = POST ] && return 0 || return 1
    fi
  fi
}
report_prog() {
  [ -n "$LOCAL" ] || return 0
  http POST "$LOCAL/p/prog" "$*"
}

# 每一行输出：追加进 install.log，同时实时 POST 回 App 的「终端」
upload_lines() {
  while IFS= read -r l; do
    printf '%s\n' "$l" >> "$LOGF" 2>/dev/null || true
    [ -n "$LOCAL" ] || continue
    if command -v curl >/dev/null 2>&1; then
      curl -sS -m 10 -X POST --data-binary "$l" "$LOCAL/p/term" >/dev/null 2>&1 || true
    elif [ -f "$ARKNET" ]; then
      bash "$ARKNET" POST "$LOCAL/p/term" "$l" >/dev/null 2>&1 || true
    fi
  done
}

# ------------------------------------------------------------------ 状态文件
# astr status / App 进度条都读它；安装器是唯一写方
set_state() { # $1=status(running|done|failed) $2=step $3=desc $4=err
  {
    echo "status=$1"
    echo "step=${2:-0}"
    echo "total=9"
    echo "desc=${3:-}"
    echo "err=${4:-}"
    echo "updated=$(date +%s)"
  } > "$STATE/state"
}

CUR_STEP="0/9"
CUR_DESC="准备中"
step() { # $1 形如 "3/9 Ubuntu 里的 Python"
  local s="$*"
  CUR_STEP="${s%% *}"
  CUR_DESC="${s#* }"
  printf '\n\033[1;34m==> %s\033[0m\n' "$s"
  set_state running "$CUR_STEP" "$CUR_DESC"
  report_prog "$s"
}
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  {
  printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2
  set_state failed "$CUR_STEP" "$CUR_DESC" "$*"
  report_prog "fail 安装中断（$CUR_STEP）：$* —— 修好后重新点「安装 / 更新」会接着装"
  exit 1
}

# ------------------------------------------------------------------ 入口
[ -d /data/data/com.termux ] || { echo "这个脚本要在 Termux 里跑"; exit 1; }
mkdir -p "$STATE"
: > "$LOGF"
# 从这一行起，所有输出（stdout+stderr）都会实时回传 App 并落 install.log
exec > >(upload_lines) 2>&1

[ -d /data/data/com.termux/files ] || die "Termux 环境不完整，先打开一次 Termux 让它跑完 bootstrap"

# 互斥锁：安装按钮被连点时第二个进来直接退出并说明情况；
# 锁超过 3 小时视为上次被强杀残留，自动清掉
LOCK="$STATE/.running"
if ! mkdir "$LOCK" 2>/dev/null; then
  born=$(cat "$LOCK/born" 2>/dev/null || echo 0)
  now=$(date +%s)
  if [ $((now - born)) -gt 10800 ]; then
    warn "发现 3 小时前的旧锁，自动清掉重跑"
    rm -rf "$LOCK"
    mkdir "$LOCK" || die "清不开旧锁：$LOCK"
  else
    echo "已经有一个安装任务在跑（约 $(( (now - born) / 60 )) 分钟前开始），进度条 / astr status 可以看进展。"
    echo "如果确认没在跑（比如 Termux 被强杀过），清锁重跑：rm -rf $LOCK"
    report_prog "fail 已有安装任务在跑，等它跑完或清锁：$LOCK"
    exit 0
  fi
fi
date +%s > "$LOCK/born"
trap 'rm -rf "$LOCK"' EXIT

# ------------------------------------------------------------------ 0/9 预置控制命令
# App 理论上已经把 astr/qq/arklink/creds.py 放进 ~/.arkdroid/ 了；
# 这里兜底：缺了就从 App 的本地服务 / GitHub raw 拉，然后全部链到 $PREFIX/bin。
# 放在最前面意味着：哪怕后面任何一步失败，astr status 也已经能查状态了。
step "0/9 预置控制命令（astr / qq / arklink）"
for c in astr qq arklink creds.py; do
  if [ ! -s "$STATE/$c" ]; then
    [ -n "$LOCAL" ] && http GET "$LOCAL/a/termux/$c" > "$STATE/$c" 2>/dev/null
    [ -s "$STATE/$c" ] || http GET "$RAW_BASE/$c" > "$STATE/$c" 2>/dev/null || true
  fi
  if [ -s "$STATE/$c" ]; then
    chmod +x "$STATE/$c" 2>/dev/null || true
  else
    rm -f "$STATE/$c"
    warn "控制脚本 $c 没拿到（不影响安装本体，装完 App 里还是能操作）"
  fi
done
for c in astr qq arklink; do
  [ -f "$STATE/$c" ] && ln -sf "$STATE/$c" "$PREFIX/bin/$c" 2>/dev/null && ok "$c 命令已就绪"
done

step "1/9 Termux 依赖（apt 源多镜像测速）"
export DEBIAN_FRONTEND=noninteractive
SOURCES="$PREFIX/etc/apt/sources.list"

# 多镜像测速：每个镜像下 256 KB 小样挑最快的。curl 还没装时直接用清华源（不测速）。
BEST_MIRROR=""
pick_mirror() { # $1=镜像列表 $2=测试文件路径
  BEST_MIRROR=""
  command -v curl >/dev/null 2>&1 || { warn "curl 还没装上，跳过测速"; return 0; }
  local best_spd=0 m spd
  for m in $1; do
    spd=$(curl -fsSL -m 20 -r 0-262143 -o /dev/null -w '%{speed_download}' "$m$2" 2>/dev/null || echo 0)
    spd=${spd%.*}; [ -z "$spd" ] && spd=0
    printf '    %s → %d KB/s\n' "${m#https://}" $((spd / 1024))
    if [ "$spd" -gt "$best_spd" ]; then best_spd=$spd; BEST_MIRROR=$m; fi
  done
}

pick_mirror "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main https://mirrors.aliyun.com/termux/apt/termux-main https://mirrors.ustc.edu.cn/termux/apt/termux-main https://mirrors.pku.edu.cn/termux/apt/termux-main https://packages.termux.dev/apt/termux-main" "/dists/stable/Release"
if [ -n "$BEST_MIRROR" ]; then
  echo "deb $BEST_MIRROR stable main" > "$SOURCES"
  ok "apt 源 → ${BEST_MIRROR#https://}（测速胜出）"
else
  grep -q "mirrors.tuna.tsinghua.edu.cn/termux" "$SOURCES" 2>/dev/null \
    || echo "deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main" > "$SOURCES"
  ok "apt 源 → 清华（默认）"
fi
echo "  （apt 正在更新索引、装 proot/curl；出现 Get: … / Setting up … 都是正常进度）"
pkg update -y || warn "pkg update 有报错，继续试"
pkg install -y proot tar xz-utils curl ca-certificates || die "装 proot 失败，检查网络后重试"
ok "proot / tar / curl 就绪"

# ------------------------------------------------------------------ 取包
fetch_pkg() { # $1=文件名 $2=落地路径  $3=失败是否致命（die|warn），默认 die
  local name="$1" dest="$2" fatal="${3:-die}"
  if [ -n "$LOCAL" ] && http GET "$LOCAL/f/$name" > "$dest" && [ -s "$dest" ]; then
    ok "从 App 取到 $name（$(du -h "$dest" | cut -f1)）"
    return 0
  fi
  case "$name" in
    ubuntu-rootfs.tar.gz)
      for u in \
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz" \
        "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz" \
        "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz" \
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
      do
        if curl -fsSL -m 1800 -o "$dest" "$u"; then
          ok "自己下到 rootfs（$(echo "$u" | cut -d/ -f3)）"
          return 0
        fi
      done
      ;;
    astrbot.tar.gz)
      for u in \
        "https://gh-proxy.com/https://codeload.github.com/AstrBotDevs/AstrBot/tar.gz/refs/heads/master" \
        "https://ghfast.top/https://codeload.github.com/AstrBotDevs/AstrBot/tar.gz/refs/heads/master" \
        "https://codeload.github.com/AstrBotDevs/AstrBot/tar.gz/refs/heads/master"
      do
        if curl -fsSL -m 1800 -o "$dest" "$u"; then
          ok "自己下到 AstrBot 源码（$(echo "$u" | cut -d/ -f3)）"
          return 0
        fi
      done
      ;;
    linuxqq_arm64.deb)
      for u in \
        "https://gh-proxy.com/https://github.com/panxian-AI/ArkLink/releases/download/qq-3.2.34-arm64/linuxqq_3.2.34-53644_arm64.deb" \
        "https://ghfast.top/https://github.com/panxian-AI/ArkLink/releases/download/qq-3.2.34-arm64/linuxqq_3.2.34-53644_arm64.deb" \
        "https://github.com/panxian-AI/ArkLink/releases/download/qq-3.2.34-arm64/linuxqq_3.2.34-53644_arm64.deb"
      do
        if curl -fsSL -m 1800 -o "$dest" "$u"; then
          ok "自己下到 QQ arm64（$(echo "$u" | cut -d/ -f3)）"
          return 0
        fi
      done
      ;;
    napcat.zip)
      for u in \
        "https://gh-proxy.com/https://github.com/panxian-AI/ArkLink/releases/download/napcat-shell/NapCat.Shell.zip" \
        "https://ghfast.top/https://github.com/panxian-AI/ArkLink/releases/download/napcat-shell/NapCat.Shell.zip" \
        "https://github.com/panxian-AI/ArkLink/releases/download/napcat-shell/NapCat.Shell.zip"
      do
        if curl -fsSL -m 1800 -o "$dest" "$u"; then
          ok "自己下到 NapCat（$(echo "$u" | cut -d/ -f3)）"
          return 0
        fi
      done
      ;;
    arklink.tar.gz)
      for u in \
        "https://gh-proxy.com/https://codeload.github.com/panxian-AI/ArkLink/tar.gz/refs/heads/main" \
        "https://ghfast.top/https://codeload.github.com/panxian-AI/ArkLink/tar.gz/refs/heads/main" \
        "https://codeload.github.com/panxian-AI/ArkLink/tar.gz/refs/heads/main"
      do
        if curl -fsSL -m 1800 -o "$dest" "$u"; then
          ok "自己下到 ArkLink 源码（$(echo "$u" | cut -d/ -f3)）"
          return 0
        fi
      done
      ;;
  esac
  if [ "$fatal" = "die" ]; then
    die "拿不到 $name（App 里先下载对应组件，或检查网络）"
  else
    warn "拿不到 $name，这一步先跳过"
    return 1
  fi
}

# ------------------------------------------------------------------ 2/9 rootfs
step "2/9 Ubuntu 根文件系统"
if [ -f "$ROOTFS/etc/os-release" ]; then
  ok "已有 rootfs，复用（要重装就删掉 $ROOTFS）"
else
  TMP="$STATE/rootfs.tar.gz"
  fetch_pkg ubuntu-rootfs.tar.gz "$TMP"
  mkdir -p "$ROOTFS"
  warn "解压 rootfs…"
  tar xzf "$TMP" -C "$ROOTFS" || die "解压失败，包可能是坏的"
  rm -f "$TMP"
  ok "解好了：$ROOTFS"
fi

# proot 里没有 init 帮我们配这些，得自己写
mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/tmp" "$ROOTFS/root"
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"
[ -f "$ROOTFS/etc/hosts" ] || printf '127.0.0.1 localhost\n' > "$ROOTFS/etc/hosts"

if [ "$USE_MIRROR" = "1" ]; then
  # Ubuntu 的 apt 源同样多镜像测速（proot 里还没有 curl，所以在 Termux 侧测）
  echo "  （正在给 Ubuntu apt 源测速…）"
  pick_mirror "https://mirrors.aliyun.com https://mirrors.tuna.tsinghua.edu.cn https://mirrors.ustc.edu.cn https://mirrors.pku.edu.cn" "/ubuntu/dists/noble/Release"
  UB_MIRROR="${BEST_MIRROR:-https://mirrors.aliyun.com}"
  UB_HOST="${UB_MIRROR#https://}"
  for f in "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources" "$ROOTFS/etc/apt/sources.list"; do
    [ -f "$f" ] || continue
    sed -i "s@//.*archive.ubuntu.com@//$UB_HOST@g; s@//security.ubuntu.com@//$UB_HOST@g; s@//ports.ubuntu.com@//$UB_HOST@g" "$f"
  done
  ok "apt 源 → $UB_HOST（测速胜出）"
fi

# ------------------------------------------------------------------ proot 入口
cat > "$STATE/proot-run.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# 在 rootfs 里执行命令：proot-run.sh <命令...>
STATE="$HOME/.arkdroid"
ROOTFS="$STATE/rootfs"
exec proot --link2symlink -0 \
  -r "$ROOTFS" \
  -b /dev -b /proc -b /sys \
  -b "$STATE:/root/.arkdroid" \
  -w /root \
  /usr/bin/env -i \
    HOME=/root \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 LANGUAGE=C.UTF-8 LC_ALL=C.UTF-8 \
    TERM="${TERM:-xterm-256color}" \
    ${ARK_ENV:-} \
  "$@"
EOS
chmod +x "$STATE/proot-run.sh"
ok "proot 入口写好：$STATE/proot-run.sh"

# ------------------------------------------------------------------ 3-6/9 AstrBot
if [ "$SKIP_ASTR" = "0" ]; then
  step "3/9 Ubuntu 里的 Python"
  "$STATE/proot-run.sh" /bin/bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends python3 python3-pip ca-certificates tzdata curl
python3 -V
  ' || die "Ubuntu 里装 python3 失败（看上面的 apt 输出）"
  ok "python3 就绪"

  step "4/9 uv（用清华 PyPI，不走 GitHub）"
  "$STATE/proot-run.sh" /bin/bash -c "
set -e
if ! command -v uv >/dev/null; then
  pip3 install --no-cache-dir -q --break-system-packages -i $PY_INDEX uv
fi
uv --version
  " || die "装 uv 失败。可以换源重试：ARKDROID_PYPI=... "
  ok "uv 就绪"

  step "5/9 AstrBot 源码"
  mkdir -p "$ROOTFS/root"
  if [ -f "$ROOTFS/root/AstrBot/main.py" ]; then
    warn "已有 AstrBot，保留 data/，只更新代码"
  fi
  TMP="$STATE/astrbot.tar.gz"
  if fetch_pkg astrbot.tar.gz "$TMP" warn; then
    rm -rf "$ROOTFS/root/AstrBot.new"
    mkdir -p "$ROOTFS/root/AstrBot.new"
    tar xzf "$TMP" -C "$ROOTFS/root/AstrBot.new" --strip-components=1 || die "解压 AstrBot 失败"
    rm -f "$TMP"
    if [ -d "$ROOTFS/root/AstrBot/data" ]; then
      cp -a "$ROOTFS/root/AstrBot/data" "$ROOTFS/root/AstrBot.new/data"
    fi
    rm -rf "$ROOTFS/root/AstrBot"
    mv "$ROOTFS/root/AstrBot.new" "$ROOTFS/root/AstrBot"
    ok "源码就位（tar 解包，不需要 git）"
  else
    warn "拿不到源码包，退回 git clone"
    "$STATE/proot-run.sh" /bin/bash -c "
      set -e
      export DEBIAN_FRONTEND=noninteractive
      apt-get install -y -qq --no-install-recommends git >/dev/null
      cd /root
      if [ -d AstrBot/.git ]; then cd AstrBot && git pull --ff-only || true; else git clone --depth 1 $ASTRBOT_REPO AstrBot; fi
    " || die "git clone 失败，检查网络"
  fi

  step "6/9 装依赖（第一次 1 GB 上下，慢是正常的）"
  "$STATE/proot-run.sh" /bin/bash -c "
set -e
cd /root/AstrBot
export UV_PYTHON_DOWNLOADS=never
export UV_DEFAULT_INDEX=$PY_INDEX
uv sync --python /usr/bin/python3
  " || die "uv sync 失败。重跑本脚本会接着来（已下的不会重下）"
  ok "依赖装完"
else
  step "3-6/9 跳过（AstrBot 已装好，--qq-only）"
fi

# ------------------------------------------------------------------ 7/9 QQ + NapCat
if [ "$SKIP_QQ" = "1" ]; then
  step "7/9 跳过（--no-qq）"
else
  step "7/9 本机 QQ：装 QQ arm64 + 注入 NapCat"

  fetch_pkg linuxqq_arm64.deb "$STATE/linuxqq_arm64.deb" warn || true

  "$STATE/proot-run.sh" /bin/bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends unzip xvfb dbus dbus-x11 fonts-noto-cjk procps
echo "  Xvfb / dbus / 中文字体就绪"
if [ -x /opt/QQ/qq ]; then
  echo "  QQ 已装，跳过 deb"
elif [ -f /root/.arkdroid/linuxqq_arm64.deb ]; then
  echo "  开始装 QQ（apt 解依赖，几分钟）…"
  apt-get install -y /root/.arkdroid/linuxqq_arm64.deb
else
  echo "NO_QQ_DEB"
  exit 3
fi
[ -x /opt/QQ/qq ] && echo "  QQ 就位：/opt/QQ/qq"
  ' || die "装 QQ 失败（要 --no-qq 就加这个参数重跑）"

  # NapCat 注入：解包 Shell，patch package.json 的 main
  if [ -f "$STATE/napcat.zip" ] || fetch_pkg napcat.zip "$STATE/napcat.zip" warn; then
    "$STATE/proot-run.sh" /bin/bash -c '
set -e
APPDIR=""
for c in /opt/QQ/resources/app; do [ -f "$c/package.json" ] && APPDIR="$c" && break; done
if [ -z "$APPDIR" ]; then
  P=$(find /opt -maxdepth 6 -path "*resources/app/package.json" 2>/dev/null | head -1)
  [ -n "$P" ] && APPDIR=$(dirname "$P")
fi
[ -n "$APPDIR" ] || { echo "NO_APPDIR"; exit 4; }
echo "  QQ app 目录：$APPDIR"
if grep -q "napcat/loadNapCat.js" "$APPDIR/package.json" 2>/dev/null; then
  echo "  NapCat 已注入，跳过"
else
  rm -rf /tmp/napcatx && mkdir -p /tmp/napcatx
  unzip -o -q /root/.arkdroid/napcat.zip -d /tmp/napcatx
  SRC=""
  if [ -f /tmp/napcatx/loadNapCat.js ]; then SRC=/tmp/napcatx;
  else SRC=$(find /tmp/napcatx -name loadNapCat.js 2>/dev/null | head -1 | xargs -r dirname); fi
  [ -n "$SRC" ] || { echo "ZIP 里没有 loadNapCat.js，确认是 NapCat.Shell.zip"; exit 5; }
  mkdir -p "$APPDIR/app_launcher/napcat"
  cp -a "$SRC"/. "$APPDIR/app_launcher/napcat/"
  python3 - "$APPDIR/package.json" <<'"'"'P'"'"'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding="utf-8"))
d["main"] = "./app_launcher/napcat/loadNapCat.js"
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("  package.json main ->", d["main"])
P
  echo "  NapCat 注入完成"
fi
    ' || die "NapCat 注入失败（确认 napcat.zip 是 NapCat.Shell.zip）"
  else
    warn "没有 napcat.zip：QQ 装好了但没注入 NapCat，拿到包后重跑本脚本"
  fi
  ok "本机 QQ 就绪（WebUI 6099，登录后 Milky 用 3000）"
fi

# ------------------------------------------------------------------ 8/9 ArkLink
if [ "$SKIP_QQ" = "1" ]; then
  step "8/9 跳过（--no-qq）"
else
  step "8/9 ArkLink（翻译层：Milky → OneBot 11）"
  TMP="$STATE/arklink.tar.gz"
  if [ ! -f "$ROOTFS/root/ArkLink/arklink/__main__.py" ]; then
    fetch_pkg arklink.tar.gz "$TMP"
    rm -rf "$ROOTFS/root/ArkLink"
    mkdir -p "$ROOTFS/root/ArkLink"
    tar xzf "$TMP" -C "$ROOTFS/root/ArkLink" --strip-components=1 || die "解压 ArkLink 失败"
    rm -f "$TMP"
    ok "ArkLink 源码就位"
  else
    ok "已有 ArkLink，复用"
  fi
  "$STATE/proot-run.sh" /bin/bash -c "
set -e
pip3 install --no-cache-dir -q --break-system-packages -i $PY_INDEX 'websockets>=12'
python3 -c 'import websockets; print(\"  websockets\", websockets.__version__)'
  " || die "ArkLink 依赖装失败"
  ok "ArkLink 就绪（python3 -m arklink，在 /root/ArkLink）"
fi

# ------------------------------------------------------------------ 9/9 收尾
step "9/9 注册命令 + 收尾"
for c in astr qq arklink; do
  if [ -s "$STATE/$c" ]; then
    chmod +x "$STATE/$c"
    ln -sf "$STATE/$c" "$PREFIX/bin/$c" 2>/dev/null || true
    ok "$c 命令已注册"
  else
    warn "$c 控制脚本缺失，从 App 本地服务再试一次"
    [ -n "$LOCAL" ] && http GET "$LOCAL/a/termux/$c" > "$STATE/$c" 2>/dev/null
    if [ -s "$STATE/$c" ]; then
      chmod +x "$STATE/$c"; ln -sf "$STATE/$c" "$PREFIX/bin/$c" 2>/dev/null || true
      ok "$c 命令已注册"
    fi
  fi
done
[ -s "$STATE/creds.py" ] || { [ -n "$LOCAL" ] && http GET "$LOCAL/a/termux/creds.py" > "$STATE/creds.py" 2>/dev/null; }

set_state done "9/9" "全部完成"
report_prog "done 全部装完了 ✓"

cat <<'EOT'

装完了。完整链路（一台手机，全程 127.0.0.1）：

  QQ arm64 → NapCat(Milky 3000) → ArkLink(OneBot 6700) → ArkBridge → AstrBot(6199)

按顺序：

  qq start        # 起 QQ（Xvfb 无头），首次登录在 http://127.0.0.1:6099/webui 扫码
                  # 登录后在 WebUI「网络配置」新建 Milky 服务器：127.0.0.1:3000
  arklink start   # 起 ArkLink，把 Milky 翻译成 OneBot 11（6700）
  astr start      # 起 AstrBot
  然后在 ArkDroid 里把中继地址填 ws://127.0.0.1:6700/onebot/v11/ws，点「保存并重启中继」

排障：qq log / arklink log / astr log；状态：qq status / arklink status / astr status
EOT

# 让 uploader 把最后几行排空再退出（它是要往回环 POST 的，别截断）
sleep 1
