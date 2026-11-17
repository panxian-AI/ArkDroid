#!/data/data/com.termux/files/usr/bin/bash
# ArkDroid 的 Termux 侧安装器。
#
# App 会先把下面这些东西下好，通过 http://127.0.0.1:<port>/t/<token>/f/... 递过来：
#   ubuntu-rootfs.tar.gz   Ubuntu 24.04 base（约 30 MB）
#   astrbot.tar.gz         AstrBot 源码（约 9 MB）
# 所以这个脚本在断网、GitHub 被墙的情况下也能装完。
#
# 手动跑也可以（没有 App 递的包就走镜像自己下）：
#   bash install.sh              # Ubuntu 源用阿里云/清华
#   bash install.sh --no-mirror
set -euo pipefail

LOCAL="${ARKDROID_LOCAL:-}"
STATE="$HOME/.arkdroid"
ROOTFS="$STATE/rootfs"
USE_MIRROR=1
PY_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
ASTRBOT_REPO="https://github.com/AstrBotDevs/AstrBot.git"

for arg in "$@"; do
  case "$arg" in
    --cn|--mirror) USE_MIRROR=1 ;;
    --no-mirror) USE_MIRROR=0 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ -d /data/data/com.termux ] || die "这个脚本要在 Termux 里跑"

mkdir -p "$STATE"
# 日志由调用方（App 那条命令）用 tee 记，这里只管往 stdout 打

step "0/6 环境检查"
command -v pkg >/dev/null || die "找不到 pkg，Termux 环境不正常"
FREE_MB=$(( $(df -Pk "$HOME" | awk 'NR==2 {print $4}') / 1024 ))
if [ "$FREE_MB" -lt 3500 ]; then
  warn "只剩 ${FREE_MB} MB，装完大约占 2.5 GB，可能不够"
else
  ok "剩余空间 ${FREE_MB} MB"
fi
[ -n "$LOCAL" ] && ok "走 App 递过来的本地包：$LOCAL" || warn "没有本地包可用，下面会自己找镜像下"

step "1/6 Termux 依赖"
pkg update -y >/dev/null 2>&1 || warn "pkg update 有报错，继续试"
pkg install -y proot tar xz-utils curl ca-certificates >/dev/null || die "装 proot 失败，检查网络后重试"
ok "proot / tar / curl 就绪"

# ------------------------------------------------------------------ 取包
fetch_pkg() { # $1=文件名 $2=落地路径
  local name="$1" dest="$2"
  if [ -n "$LOCAL" ] && curl -fsSL -m 900 -o "$dest" "$LOCAL/f/$name"; then
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
        if curl -fsSL -m 900 -o "$dest" "$u"; then
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
        if curl -fsSL -m 900 -o "$dest" "$u"; then
          ok "自己下到 AstrBot 源码（$(echo "$u" | cut -d/ -f3)）"
          return 0
        fi
      done
      ;;
  esac
  return 1
}

# ------------------------------------------------------------------ rootfs
step "2/6 Ubuntu 根文件系统"
if [ -f "$ROOTFS/etc/os-release" ]; then
  ok "已有 rootfs，复用（要重装就删掉 $ROOTFS）"
else
  TMP="$STATE/rootfs.tar.gz"
  fetch_pkg ubuntu-rootfs.tar.gz "$TMP" || die "拿不到 rootfs（App 里点「下载 Ubuntu」或检查网络）"
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
  # Ubuntu 24.04 用 deb822 的 sources.list.d/ubuntu.sources
  for f in "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources" "$ROOTFS/etc/apt/sources.list"; do
    [ -f "$f" ] || continue
    sed -i 's@//.*archive.ubuntu.com@//mirrors.aliyun.com@g; s@//security.ubuntu.com@//mirrors.aliyun.com@g; s@//ports.ubuntu.com@//mirrors.aliyun.com@g' "$f"
  done
  ok "apt 源换成阿里云"
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
step "3/6 Ubuntu 里的 Python"
"$STATE/proot-run.sh" /bin/bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends python3 python3-pip ca-certificates tzdata curl >/dev/null
python3 -V
' || die "Ubuntu 里装 python3 失败（看上面的 apt 输出）"
ok "python3 就绪"

step "4/6 uv（用清华 PyPI，不走 GitHub）"
"$STATE/proot-run.sh" /bin/bash -c "
set -e
if ! command -v uv >/dev/null; then
  pip3 install --no-cache-dir -q --break-system-packages -i $PY_INDEX uv
fi
uv --version
" || die "装 uv 失败。可以换源重试：ARKDROID_PYPI=... "
ok "uv 就绪"

# ------------------------------------------------------------------ AstrBot
step "5/6 AstrBot 源码"
mkdir -p "$ROOTFS/root"
if [ -f "$ROOTFS/root/AstrBot/main.py" ]; then
  warn "已有 AstrBot，保留 data/，只更新代码"
fi
TMP="$STATE/astrbot.tar.gz"
if fetch_pkg astrbot.tar.gz "$TMP"; then
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

step "6/6 装依赖（第一次 1 GB 上下，慢是正常的）"
"$STATE/proot-run.sh" /bin/bash -c "
set -e
cd /root/AstrBot
export UV_PYTHON_DOWNLOADS=never
export UV_DEFAULT_INDEX=$PY_INDEX
uv sync --python /usr/bin/python3
" || die "uv sync 失败。重跑本脚本会接着来（已下的不会重下）"
ok "依赖装完"

# ------------------------------------------------------------------ astr
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
  # proot 可能留下子进程
  pkill -f "$ROOTFS" 2>/dev/null || true
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
ok "astr 命令已注册"

cat <<'EOT'

装完了。

  astr start      # 启动 AstrBot（首次启动会生成 data/）
  astr status     # 状态 + 面板端口
  astr creds      # 面板账号（App 里那个「读取账号」就是跑它）
  astr log        # 看日志
  astr stop

回到 ArkDroid：点「启动」（中继）→「读取账号」→「内置浏览器」。
链路：QQ ↔ ArkLink ↔ ArkDroid(ArkBridge) ↔ AstrBot
EOT
