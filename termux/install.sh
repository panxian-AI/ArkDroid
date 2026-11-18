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
[ -d /data/data/com.termux/files/usr ] || { echo "这个脚本要在 Termux 环境里跑"; exit 1; }
mkdir -p "$STATE"
: > "$LOGF"
# 从这一行起，所有输出（stdout+stderr）都会实时回传 App 并落 install.log
exec > >(upload_lines) 2>&1

[ -d /data/data/com.termux/files/usr ] || die "Termux 环境不完整（bootstrap 没解压好）"

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

step "1/9 检查 Termux 运行时自带工具"
# 嵌入式运行时用的是官方 bootstrap，tar/gzip/xz/curl/coreutils/unzip 都已自带，
# 不需要 apt 装任何东西（而且 proot -0 下 pkg 会拒绝以 root 运行）。
MISSING=""
for t in bash tar gzip curl dpkg-deb unzip sed find pgrep; do
  command -v "$t" >/dev/null 2>&1 || MISSING="$MISSING $t"
done
[ -z "$MISSING" ] || die "运行时缺命令：$MISSING（bootstrap 可能没解压完整，回 App 重新装一次）"
ok "自带工具齐全（bootstrap 已含 tar/curl/xz/unzip，无需联网装包）"

# 多镜像测速：每个镜像下 256 KB 小样挑最快的（给 Ubuntu 的 apt 源用）
BEST_MIRROR=""
pick_mirror() { # $1=镜像列表 $2=测试文件路径
  BEST_MIRROR=""
  command -v curl >/dev/null 2>&1 || { warn "没有 curl，跳过测速"; return 0; }
  local best_spd=0 m spd
  for m in $1; do
    spd=$(curl -fsSL -m 20 -r 0-262143 -o /dev/null -w '%{speed_download}' "$m$2" 2>/dev/null || echo 0)
    spd=${spd%.*}; [ -z "$spd" ] && spd=0
    printf '    %s → %d KB/s\n' "${m#https://}" $((spd / 1024))
    if [ "$spd" -gt "$best_spd" ]; then best_spd=$spd; BEST_MIRROR=$m; fi
  done
}

# ------------------------------------------------------------------ 取包
# 所有必需产物都托管在 GitHub（ArkLink 仓库 dist-v1），第三方镜像只作兜底——
# 镜像站改路径/限速/挂掉都不再影响安装。
GH_DIST="https://github.com/panxian-AI/ArkLink/releases/download/dist-v1"
gh_urls() { # $1=文件名 → 依次输出 gh-proxy / ghfast / 直连
  echo "https://gh-proxy.com/$GH_DIST/$1"
  echo "https://ghfast.top/$GH_DIST/$1"
  echo "$GH_DIST/$1"
}

fetch_pkg() { # $1=文件名 $2=落地路径  $3=失败是否致命（die|warn），默认 die
  local name="$1" dest="$2" fatal="${3:-die}"
  if [ -n "$LOCAL" ] && http GET "$LOCAL/f/$name" > "$dest" && [ -s "$dest" ]; then
    ok "从 App 取到 $name（$(du -h "$dest" | cut -f1)）"
    return 0
  fi
  case "$name" in
    ubuntu-rootfs.tar.gz)
      for u in $(gh_urls ubuntu-rootfs.tar.gz) \
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz" \
        "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
      do
        if curl -fsSL -m 1800 -o "$dest" "$u"; then
          ok "自己下到 rootfs（$(echo "$u" | cut -d/ -f3)）"
          return 0
        fi
      done
      ;;
    astrbot.tar.gz)
      for u in $(gh_urls astrbot.tar.gz) \
        "https://gh-proxy.com/https://codeload.github.com/AstrBotDevs/AstrBot/tar.gz/refs/heads/master"
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
      for u in $(gh_urls arklink.tar.gz) \
        "https://gh-proxy.com/https://codeload.github.com/panxian-AI/ArkLink/tar.gz/refs/heads/main"
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
  # 硬链接失败在 Android 上很常见（proot 的 link2symlink 已经把大部分改写成软链），
  # 这类报错不必展示也不必中断；其余错误照常打出来。
  tar xzf "$TMP" -C "$ROOTFS" 2> "$STATE/rootfs-extract.err" || true
  grep -v 'Cannot hard link' "$STATE/rootfs-extract.err" 2>/dev/null | head -20 || true
  rm -f "$STATE/rootfs-extract.err"
  # tar 只要有一条报错就整体返回非 0，所以按实际内容判断，别被退出码骗了
  if [ ! -f "$ROOTFS/etc/os-release" ] || [ ! -x "$ROOTFS/bin/bash" ]; then
    die "解压失败（rootfs 关键文件缺失），包可能是坏的"
  fi
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

# ------------------------------------------------------------------ Ubuntu 入口
# 为什么是「转发」而不是直接 proot：本脚本跑在 Termux 运行时里，而那个环境本身就是
# proot（外层已经用 ptrace 接管所有子孙进程），再起一个 proot 必然失败。所以把命令
# 交给 App，由 App 起一个平级的 proot 进程去 Ubuntu rootfs 里跑。
cat > "$STATE/proot-run.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# proot-run.sh /bin/bash -c '命令'                → 同步执行，返回退出码
# proot-run.sh --bg <pid文件> /bin/bash -c '命令'  → 后台常驻，pid 写进文件
STATE="$HOME/.arkdroid"
LOCAL="${ARKDROID_LOCAL:-}"
[ -n "$LOCAL" ] || { echo "proot-run: ARKDROID_LOCAL 为空（App 的本地服务没起来）" >&2; exit 9; }

PIFILE=""
if [ "${1:-}" = "--bg" ]; then PIFILE="${2:-}"; shift 2 || true; fi

if [ "${1:-}" = "/bin/bash" ] || [ "${1:-}" = "bash" ]; then
  shift
  case "${1:-}" in
    -c) shift; CMD="$*" ;;
    -l|"") echo "proot-run: 不支持交互 shell（App 不是终端），用 astr run '命令'" >&2; exit 2 ;;
    *) CMD="bash $*" ;;
  esac
else
  CMD="$*"
fi
# astr resetpw 之类会通过 ARK_ENV 传环境变量（形如 A=1 B=2）
[ -n "${ARK_ENV:-}" ] && CMD="export $ARK_ENV; $CMD"

if [ -n "$PIFILE" ]; then
  LOGF="${ARK_BG_LOG:-$STATE/ubuntu-bg.log}"
  out=$(printf '%s' "$CMD" | curl -sS --max-time 120 -X POST --data-binary @- \
        "$LOCAL/p/ubuntu-bg?log=$LOGF" 2>/dev/null) || {
    echo "proot-run: 后台启动失败（App 回环不通）" >&2; exit 9; }
  pid="${out#pid=}"
  case "$pid" in
    ''|-1|*[!0-9]*) echo "proot-run: 后台启动失败：$out" >&2; exit 9 ;;
  esac
  echo "$pid" > "$PIFILE"
  echo "已在后台启动（pid $pid），日志：$LOGF"
  exit 0
fi

out=$(printf '%s' "$CMD" | curl -sS --max-time 0 -X POST --data-binary @- \
      "$LOCAL/p/ubuntu" 2>/dev/null) || {
  echo "proot-run: App 没回话（本地服务不通，App 可能被杀了）" >&2; exit 9; }
case "$out" in
  exit=0) exit 0 ;;
  exit=*) exit "${out#exit=}" ;;
  *) echo "proot-run: 意外的回复：$out" >&2; exit 9 ;;
esac
EOS
chmod +x "$STATE/proot-run.sh"
ok "Ubuntu 入口写好：$STATE/proot-run.sh（命令交给 App 起平级 proot 执行）"

# ------------------------------------------------------------------ 2.5/9 dpkg 自愈
# rootfs 里 debconf 缺失时，tzdata/ca-certificates 等包的 postinst 会 exec
# /usr/share/debconf/frontend 直接 127，十来个包卡在半配置状态，后面 apt 全挂。
# 先单独把 debconf 装上（download + dpkg -i，不碰半配置包），再统一 configure -a。
step "2.5/9 dpkg 半配置自愈"
"$STATE/proot-run.sh" /bin/bash -c '
export DEBIAN_FRONTEND=noninteractive
# 清理可能的残留锁（上次安装中途被杀会留下，apt 会永远等锁）
rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock
# 注意：不能用 confmodule 文件判断——镜像里可能残留 confmodule 而 debconf 包本体缺失
if [ ! -x /usr/share/debconf/frontend ] || ! dpkg-query -W -f="${Status}" debconf 2>/dev/null | grep -q "install ok installed"; then
  echo "  debconf 缺失/不完整，尝试补装（每步最多等 3 分钟，失败走兜底）"
  timeout 180 apt-get update -q || echo "  apt update 超时/失败，继续"
  (cd /tmp && timeout 180 apt-get download debconf && dpkg -i /tmp/debconf_*.deb) \
    || echo "  在线补装没成功"
  rm -f /tmp/debconf_*.deb
fi
# 关键：/usr/share/debconf/frontend 和 dpkg-preconfigure 都是 perl 脚本，
# 这个精简 rootfs 没有 perl —— 文件在但 exec 报 not found（解释器缺失）
if ! command -v perl >/dev/null 2>&1; then
  echo "  缺 perl-base（frontend/dpkg-preconfigure 是 perl 脚本），补装"
  (cd /tmp && timeout 180 apt-get download perl-base && dpkg -i /tmp/perl-base_*.deb) \
    || echo "  perl-base 补装没成功"
  rm -f /tmp/perl-base_*.deb
fi
if [ ! -x /usr/share/debconf/frontend ]; then
  echo "  用空 frontend 兜底（跳过交互提问，用默认值）"
  mkdir -p /usr/share/debconf
  cat > /usr/share/debconf/frontend <<EOF2
#!/bin/sh
exit 0
EOF2
  chmod +x /usr/share/debconf/frontend
fi
timeout 600 dpkg --configure -a || echo "  dpkg --configure -a 仍有报错，交给后续 apt 再试"
# 空 frontend 路径下 ca-certificates 的证书包可能没重建，补一次
command -v update-ca-certificates >/dev/null 2>&1 && timeout 300 update-ca-certificates --fresh >/dev/null 2>&1 || true
'
ok "dpkg 状态自检完成"

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
# Android 文件系统不支持硬链接（proot 只是模拟），uv 默认的 hardlink 装法会报
# 'failed to hardlink file ... Invalid argument (os error 22)'。改成复制。
export UV_LINK_MODE=copy
uv sync --python /usr/bin/python3 --link-mode copy
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
# QQ（Electron）运行要的库。libasound 在 noble 里叫 libasound2t64，老名 libasound2 兜底；
# 其余几个哪怕 apt 名字对不上也不让它拦住安装（QQ deb 自己的依赖多半已带上）
for p in libasound2t64 libasound2; do
  if apt-get install -y --no-install-recommends "$p" 2>&1 | tail -2; then
    ldconfig -p 2>/dev/null | grep -q "libasound.so.2 " && { echo "  libasound.so.2 就绪"; break; }
  fi
done
apt-get install -y --no-install-recommends \
  libnss3 libatk-bridge2.0-0t64 libatk1.0-0t64 libcups2t64 libdrm2 libgbm1 \
  libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libatspi2.0-0t64 \
  >/dev/null 2>&1 || true
for lib in libasound.so.2 libnss3.so; do
  ldconfig -p 2>/dev/null | grep -q "$lib " || echo "  ✗ $lib 仍缺，qq start 会自动补装"
done
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
NAPCAT_DIR="$APPDIR/app_launcher/napcat"
fix_loader() { # .js 加载器在 type:module 目录里会被当 ESM 拆崩（require is not defined）→ 改 .cjs
  if [ -f "$NAPCAT_DIR/loadNapCat.js" ] && grep -q '"type" *: *"module"' "$NAPCAT_DIR/package.json" 2>/dev/null; then
    mv "$NAPCAT_DIR/loadNapCat.js" "$NAPCAT_DIR/loadNapCat.cjs"
    echo "  加载器已改名为 loadNapCat.cjs（CommonJS，避开 ESM 坑）"
  fi
}
if [ -f "$NAPCAT_DIR/loadNapCat.cjs" ] && grep -q "napcat/loadNapCat.cjs" "$APPDIR/package.json" 2>/dev/null; then
  echo "  NapCat 已注入（cjs），跳过"
elif [ -f "$NAPCAT_DIR/loadNapCat.js" ] && grep -q "napcat/loadNapCat.js" "$APPDIR/package.json" 2>/dev/null; then
  # 旧版注入的坏状态：修 loader 后缀 + 改 main 指向
  fix_loader
  python3 - "$APPDIR/package.json" <<'"'"'P'"'"'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding="utf-8"))
d["main"] = "./app_launcher/napcat/loadNapCat.cjs"
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("  package.json main ->", d["main"])
P
  echo "  NapCat 注入已修复（ESM 兼容）"
else
  rm -rf /tmp/napcatx && mkdir -p /tmp/napcatx
  unzip -o -q /root/.arkdroid/napcat.zip -d /tmp/napcatx
  SRC=""
  if [ -f /tmp/napcatx/loadNapCat.js ]; then SRC=/tmp/napcatx;
  else SRC=$(find /tmp/napcatx -name loadNapCat.js 2>/dev/null | head -1 | xargs -r dirname); fi
  [ -n "$SRC" ] || { echo "ZIP 里没有 loadNapCat.js，确认是 NapCat.Shell.zip"; exit 5; }
  mkdir -p "$NAPCAT_DIR"
  cp -a "$SRC"/. "$NAPCAT_DIR/"
  fix_loader
  python3 - "$APPDIR/package.json" <<'"'"'P'"'"'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding="utf-8"))
d["main"] = "./app_launcher/napcat/loadNapCat.cjs"
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("  package.json main ->", d["main"])
P
  echo "  NapCat 注入完成"
fi
# WebUI 登录密钥固定成 arkdroid（免翻启动日志找随机 token）
NAPCFG="$NAPCAT_DIR/config"
mkdir -p "$NAPCFG"
if [ ! -f "$NAPCFG/webui.json" ] || ! grep -q '"token" *: *"arkdroid"' "$NAPCFG/webui.json"; then
  cat > "$NAPCFG/webui.json" <<'"'"'WJSON'"'"'
{
  "host": "0.0.0.0",
  "port": 6099,
  "prefix": "",
  "token": "arkdroid",
  "loginRate": 3,
  "autoLoginAccount": ""
}
WJSON
  echo "  WebUI 登录密钥已固定：arkdroid"
fi
    ' || die "NapCat 注入失败（确认 napcat.zip 是 NapCat.Shell.zip）"
  else
    warn "没有 napcat.zip：QQ 装好了但没注入 NapCat，拿到包后重跑本脚本"
  fi
  ok "本机 QQ 就绪（WebUI 6099，OneBot 直连 3001）"
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

  QQ arm64 → NapCat(OneBot WS 3001) → ArkBridge → AstrBot(6185)

傻瓜式用法（App 会自动做，手敲才需要看这里）：

  astr start      # 起 AstrBot，面板 http://127.0.0.1:6185
  qq start        # 起 QQ（Xvfb 无头），首次在 http://127.0.0.1:6099/webui 扫码登录（密钥 arkdroid）
                  # 登录后 OneBot 3001 自动就绪（配置已预置，不用进 WebUI 配网络）
  然后 ArkDroid 的中继地址保持 ws://127.0.0.1:3001 直连即可（App 已自动填好）

排障：qq log / astr log；状态：qq status / astr status
EOT

# 让 uploader 把最后几行排空再退出（它是要往回环 POST 的，别截断）
sleep 1
