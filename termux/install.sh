#!/data/data/com.termux/files/usr/bin/bash
# ArkDroid 的 Termux 侧安装器。
#
# App 会先把下面这些东西下好，通过 http://127.0.0.1:<port>/t/<token>/f/... 递过来：
#   ubuntu-rootfs.tar.gz   Ubuntu 24.04 base（约 30 MB）
#   astrbot.tar.gz         AstrBot 源码（约 9 MB）
#   linuxqq_arm64.deb      QQ NT arm64（约 208 MB，本机 QQ 用）
#   napcat.zip             NapCat.Shell（本机 QQ 用）
#   arklink.tar.gz         ArkLink 源码（本机 QQ 链路）
# 所以这个脚本在断网、GitHub 被墙的情况下也能装完。
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
USE_MIRROR=1
SKIP_ASTR=0
SKIP_QQ=0
PY_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
ASTRBOT_REPO="https://github.com/AstrBotDevs/AstrBot.git"

for arg in "$@"; do
  case "$arg" in
    --cn|--mirror) USE_MIRROR=1 ;;
    --no-mirror) USE_MIRROR=0 ;;
    --no-qq) SKIP_QQ=1 ;;
    --qq-only) SKIP_ASTR=1 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

# 进度上报：把步骤实时推给 App（p/prog），失败不影响安装。
# curl 是第 1 步才装的，之前全靠 arknet.sh（bash /dev/tcp）兜底，两条路都不通才算真失败
ARKNET="$HOME/.arkdroid/arknet.sh"
http() { # http METHOD URL [body] ；GET 的响应打到 stdout（调用处 > 重定向）
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
  [ -n "$ARKDROID_LOCAL" ] || return 0
  http POST "$ARKDROID_LOCAL/p/prog" "$*"
}
step() {
  printf '\n\033[1;34m==> %s\033[0m\n' "$*"
  report_prog "$*"
}
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; report_prog "done 安装中断：$*"; exit 1; }

# 多镜像测速（和 App 下载卡同款逻辑）：每个镜像下 256 KB 小样，挑最快的。
# 结果放全局 BEST_MIRROR；$1=镜像列表（空格分隔） $2=每个镜像上的测试文件路径
BEST_MIRROR=""
pick_mirror() {
  BEST_MIRROR=""
  local best_spd=0 m spd
  for m in $1; do
    spd=$(curl -fsSL -m 20 -r 0-262143 -o /dev/null -w '%{speed_download}' "$m$2" 2>/dev/null || echo 0)
    spd=${spd%.*}; [ -z "$spd" ] && spd=0
    printf '    %s → %d KB/s\n' "${m#https://}" $((spd / 1024))
    if [ "$spd" -gt "$best_spd" ]; then best_spd=$spd; BEST_MIRROR=$m; fi
  done
}

[ -d /data/data/com.termux ] || die "这个脚本要在 Termux 里跑"

mkdir -p "$STATE"
# 互斥锁：App 的安装按钮被连点多次时，两个 install.sh 会互相踩（日志会重复、错乱）
LOCK="$STATE/.running"
if ! mkdir "$LOCK" 2>/dev/null; then
  die "已经有一个安装任务在跑了，等它结束；上次是被强杀的话手动清锁：rm -rf $LOCK"
fi
trap 'rm -rf "$LOCK"' EXIT
# 日志由调用方（App 那条命令）用 tee 记，这里只管往 stdout 打

if [ "$SKIP_ASTR" = "1" ]; then
  step "0/9 环境检查（只补本机 QQ + ArkLink）"
else
  step "0/9 环境检查"
fi
command -v pkg >/dev/null || die "找不到 pkg，Termux 环境不正常"
FREE_MB=$(( $(df -Pk "$HOME" | awk 'NR==2 {print $4}') / 1024 ))
if [ "$SKIP_QQ" = "0" ]; then
  if [ "$FREE_MB" -lt 6000 ]; then
    warn "只剩 ${FREE_MB} MB。全量（含 QQ）大约占 4.5 GB，可能不够；不够就改用 --no-qq"
  else
    ok "剩余空间 ${FREE_MB} MB"
  fi
else
  if [ "$FREE_MB" -lt 3500 ]; then
    warn "只剩 ${FREE_MB} MB，装完大约占 2.5 GB，可能不够"
  else
    ok "剩余空间 ${FREE_MB} MB"
  fi
fi
[ -n "$LOCAL" ] && ok "走 App 递过来的本地包：$LOCAL" || warn "没有本地包可用，下面会自己找镜像下"

step "1/9 Termux 依赖（apt 源多镜像测速）"
export DEBIAN_FRONTEND=noninteractive
SOURCES="$PREFIX/etc/apt/sources.list"
pick_mirror "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main https://mirrors.aliyun.com/termux/apt/termux-main https://mirrors.ustc.edu.cn/termux/apt/termux-main https://mirrors.pku.edu.cn/termux/apt/termux-main https://packages.termux.dev/apt/termux-main" "/dists/stable/Release"
if [ -n "$BEST_MIRROR" ]; then
  echo "deb $BEST_MIRROR stable main" > "$SOURCES"
  ok "apt 源 → ${BEST_MIRROR#https://}（测速胜出）"
else
  warn "镜像没测出速度，保留原源接着试"
fi
echo "  （apt 正在更新索引、装 proot/curl；下面出现 Get: … / Setting up … 都是正常进度）"
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

# ------------------------------------------------------------------ rootfs
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

# ------------------------------------------------------------------ 系统依赖
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

  # ------------------------------------------------------------------ AstrBot
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

# ------------------------------------------------------------------ QQ + NapCat
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

# ------------------------------------------------------------------ ArkLink
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

# ------------------------------------------------------------------ 命令
step "9/9 注册命令：astr / qq / arklink"

cat > "$STATE/astr" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# ArkDroid 的 AstrBot 控制命令：start/stop/status/log/creds/resetpw/shell/run
STATE="$HOME/.arkdroid"
ROOTFS="$STATE/rootfs"
LOG="$STATE/astr.log"
PID="$STATE/astr.pid"

running() { [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; }

start() {
  if running; then echo "已经在跑了（pid $(cat "$PID")）"; return 0; fi
  : > "$LOG"
  nohup "$STATE/proot-run.sh" /bin/bash -c \
    'cd /root/AstrBot && exec ./.venv/bin/python main.py' >> "$LOG" 2>&1 &
  echo $! > "$PID"
  echo "已启动（pid $(cat "$PID")），日志：$LOG"
  sleep 3
  tail -n 12 "$LOG" 2>/dev/null || true
}

stop() {
  if ! running; then echo "没在跑"; return 0; fi
  kill "$(cat "$PID")" 2>/dev/null || true
  sleep 1
  kill -9 "$(cat "$PID")" 2>/dev/null || true
  rm -f "$PID"
  echo "已停止"
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 1; start ;;
  status)
    running && echo "进程在跑（pid $(cat "$PID")）" || echo "进程没在跑"
    code=$(curl -s -o /dev/null -m 4 -w '%{http_code}' http://127.0.0.1:6185/ 2>/dev/null || echo 000)
    echo "面板 127.0.0.1:6185 → HTTP $code"
    [ -f "$LOG" ] && echo "日志最后一行：$(tail -n 1 "$LOG" 2>/dev/null)"
    ;;
  log) tail -n "${2:-80}" "$LOG" 2>/dev/null || echo "还没日志" ;;
  creds) "$STATE/proot-run.sh" /usr/bin/python3 /root/.arkdroid/creds.py ;;
  resetpw)
    pw="${2:-}"
    [ -n "$pw" ] || { echo "用法: astr resetpw 新密码"; exit 2; }
    stop
    echo "用新密码重启一次，AstrBot 会把新密码写进配置…"
    ARK_ENV="ASTRBOT_RESET_DASHBOARD_PASSWORD=$pw" start
    sleep 6
    echo "完成。账号 astrbot / 密码 $pw"
    ;;
  shell) "$STATE/proot-run.sh" /bin/bash -l ;;
  run) shift; "$STATE/proot-run.sh" /bin/bash -c "$*" ;;
  *) sed -n '2,3p' "$0"; echo "用法: astr {start|stop|restart|status|log|creds|resetpw|shell|run}" ;;
esac
EOS
chmod +x "$STATE/astr"
ln -sf "$STATE/astr" "$PREFIX/bin/astr" 2>/dev/null || true

# 本机 QQ：QQ(Xvfb 无头) + NapCat 注入层
cat > "$STATE/qq" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# 本机 QQ 控制命令：start/stop/status/log
STATE="$HOME/.arkdroid"
LOG="$STATE/qq.log"
PID="$STATE/qq.pid"

running() { [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; }

start() {
  if running; then echo "已经在跑了（pid $(cat "$PID")）"; return 0; fi
  : > "$LOG"
  nohup "$STATE/proot-run.sh" /bin/bash -c '
cd /root
pgrep -x Xvfb >/dev/null || { Xvfb :1 -screen 0 1280x720x16 -nolisten tcp >/root/.arkdroid/xvfb.log 2>&1 & }
sleep 2
export DISPLAY=:1 HOME=/root
exec /opt/QQ/qq --no-sandbox \
  --disable-gpu \
  --disable-software-rasterizer \
  --disable-gpu-compositing \
  --disable-dev-shm-usage
  ' >> "$LOG" 2>&1 &
  echo $! > "$PID"
  echo "已启动（pid $(cat "$PID")）。首次登录：浏览器开 http://127.0.0.1:6099/webui 扫码"
  sleep 4
  tail -n 12 "$LOG" 2>/dev/null || true
}

stop() {
  if running; then kill "$(cat "$PID")" 2>/dev/null || true; rm -f "$PID"; fi
  pkill -f "/opt/QQ/qq" 2>/dev/null || true
  pkill -f "Xvfb :1" 2>/dev/null || true
  echo "已停止"
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 2; start ;;
  status)
    running && echo "进程在跑（pid $(cat "$PID")）" || echo "进程没在跑"
    code=$(curl -s -o /dev/null -m 4 -w '%{http_code}' http://127.0.0.1:6099/ 2>/dev/null || echo 000)
    echo "NapCat WebUI 127.0.0.1:6099 → HTTP $code（401/200 都算在线）"
    [ -f "$LOG" ] && echo "日志最后一行：$(tail -n 1 "$LOG" 2>/dev/null)"
    ;;
  log) tail -n "${2:-80}" "$LOG" 2>/dev/null || echo "还没日志" ;;
  *) sed -n '2,3p' "$0"; echo "用法: qq {start|stop|restart|status|log}" ;;
esac
EOS
chmod +x "$STATE/qq"
ln -sf "$STATE/qq" "$PREFIX/bin/qq" 2>/dev/null || true

# ArkLink：NapCat 的 Milky → OneBot 11 服务端（ArkBridge 连 6700）
cat > "$STATE/arklink" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# ArkLink 控制命令：start/stop/status/log
STATE="$HOME/.arkdroid"
LOG="$STATE/arklink.log"
PID="$STATE/arklink.pid"

running() { [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; }

start() {
  if running; then echo "已经在跑了（pid $(cat "$PID")）"; return 0; fi
  : > "$LOG"
  nohup "$STATE/proot-run.sh" /bin/bash -c 'cd /root/ArkLink && exec python3 -m arklink serve onebot --type milky --set base_url=http://127.0.0.1:3000 --port 6700 --token ""' >> "$LOG" 2>&1 &
  echo $! > "$PID"
  echo "已启动（pid $(cat "$PID")）。ArkBridge 连 ws://127.0.0.1:6700/onebot/v11/ws"
  sleep 3
  tail -n 12 "$LOG" 2>/dev/null || true
}

stop() {
  if running; then kill "$(cat "$PID")" 2>/dev/null || true; rm -f "$PID"; fi
  pkill -f "arklink serve onebot" 2>/dev/null || true
  echo "已停止"
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 1; start ;;
  status)
    running && echo "进程在跑（pid $(cat "$PID")）" || echo "进程没在跑"
    code=$(curl -s -o /dev/null -m 4 -w '%{http_code}' http://127.0.0.1:6700/ 2>/dev/null || echo 000)
    echo "OneBot 服务端 127.0.0.1:6700 → HTTP $code"
    [ -f "$LOG" ] && echo "日志最后一行：$(tail -n 1 "$LOG" 2>/dev/null)"
    ;;
  log) tail -n "${2:-80}" "$LOG" 2>/dev/null || echo "还没日志" ;;
  *) sed -n '2,3p' "$0"; echo "用法: arklink {start|stop|restart|status|log}" ;;
esac
EOS
chmod +x "$STATE/arklink"
ln -sf "$STATE/arklink" "$PREFIX/bin/arklink" 2>/dev/null || true

# 读面板账号的小脚本（在 proot 里跑）
cat > "$STATE/creds.py" <<'EOS'
import json
import os

path = "/root/AstrBot/data/cmd_config.json"
if not os.path.exists(path):
    print("OK=0")
    print("FIRST_RUN=1")
    raise SystemExit(0)
try:
    with open(path, encoding="utf-8") as fh:
        cfg = json.load(fh)
except Exception as exc:  # 配置坏了也别把 App 搞崩
    print("OK=0")
    print("NOTE=" + str(exc))
    raise SystemExit(0)

db = cfg.get("dashboard") or {}
pw = db.get("password") or ""
hashed = bool(db.get("pbkdf2_password"))
print("OK=1")
print("USER=" + str(db.get("username") or "astrbot"))
print("PASS=" + pw)
print("HASHED=" + ("1" if hashed else "0"))
print("FIRST_RUN=" + ("1" if (not hashed and not pw) else "0"))
EOS
ok "astr / qq / arklink 命令已注册"

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

report_prog "done 全部装完了 ✓"
