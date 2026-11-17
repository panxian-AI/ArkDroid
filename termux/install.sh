#!/data/data/com.termux/files/usr/bin/bash
# ArkDroid 的 Termux 侧安装器：一条命令把 AstrBot 装进 proot Ubuntu。
#
#   bash install.sh            # 默认走国内镜像（apt 用阿里云，pip 用清华）
#   bash install.sh --no-mirror
#   ASTRBOT_REPO=https://gitee.com/xxx/AstrBot.git bash install.sh
#
# 做四件事：
#   1. Termux 里补 proot-distro / curl / git
#   2. 建（或复用）Ubuntu rootfs —— 因为 AstrBot 的轮子是 manylinux 的，Android 原生装不上
#   3. Ubuntu 里装 python3 + uv，拉 AstrBot 并 uv sync
#   4. 注册 astr 命令（start/stop/status/log/shell/update）
set -euo pipefail

REPO_RAW="${ARKDROID_RAW:-https://raw.githubusercontent.com/panxian-AI/ArkDroid/main}"
ASTRBOT_REPO="${ASTRBOT_REPO:-https://github.com/AstrBotDevs/AstrBot.git}"
DISTRO="${ARKDROID_DISTRO:-ubuntu}"
STATE_DIR="$HOME/.arkdroid"
USE_MIRROR=1

for arg in "$@"; do
  case "$arg" in
    --cn|--mirror) USE_MIRROR=1 ;;
    --no-mirror) USE_MIRROR=0 ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

if [ ! -d /data/data/com.termux ]; then
  die "这个脚本要在 Termux 里跑（现在不在 Termux 环境）"
fi

step "0/5 检查环境"
command -v pkg >/dev/null || die "找不到 pkg，Termux 环境不正常"
FREE_KB=$(df -Pk "$HOME" | awk 'NR==2 {print $4}')
if [ "${FREE_KB:-0}" -lt 3500000 ]; then
  warn "剩余空间约 $((FREE_KB / 1024)) MB，装完 AstrBot 大概要 2.5 GB，可能不够"
else
  ok "剩余空间 $((FREE_KB / 1024)) MB"
fi
mkdir -p "$STATE_DIR"

step "1/5 Termux 依赖"
pkg update -y >/dev/null 2>&1 || warn "pkg update 有报错，继续试"
pkg install -y proot-distro curl git >/dev/null || die "装 proot-distro 失败，检查网络后重试"
ok "proot-distro 就绪"

step "2/5 Ubuntu rootfs"
if proot-distro list --installed 2>/dev/null | grep -qx "$DISTRO"; then
  ok "$DISTRO 已存在，复用"
else
  warn "首次安装 $DISTRO，要下载几百 MB"
  proot-distro install "$DISTRO" || die "安装 $DISTRO 失败"
  ok "$DISTRO 装好了"
fi

step "3/5 Ubuntu 内：Python + uv"
MIRROR_APT=""
if [ "$USE_MIRROR" = "1" ]; then
  MIRROR_APT=$(cat <<'EOS'
if [ -f /etc/apt/sources.list ]; then
  sed -i 's@//.*archive.ubuntu.com@//mirrors.aliyun.com@g; s@//security.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list
fi
for f in /etc/apt/sources.list.d/*.sources; do
  [ -f "$f" ] || continue
  sed -i 's@//.*archive.ubuntu.com@//mirrors.aliyun.com@g; s@//security.ubuntu.com@//mirrors.aliyun.com@g' "$f"
done
EOS
)
fi

proot-distro login "$DISTRO" --shared-tmp -- /bin/bash -lc "
set -e
export DEBIAN_FRONTEND=noninteractive
$MIRROR_APT
apt-get update -qq
apt-get install -y -qq --no-install-recommends python3 python3-venv git curl ca-certificates tmux tzdata >/dev/null
python3 --version
if ! command -v uv >/dev/null; then
  export UV_INSTALL_DIR=/usr/local/bin
  curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null
fi
uv --version
" || die "Ubuntu 内初始化失败，看上面的报错"

step "4/5 拉 AstrBot 并装依赖"
INDEX_ENV=""
if [ "$USE_MIRROR" = "1" ]; then
  INDEX_ENV="export UV_DEFAULT_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple"
fi

proot-distro login "$DISTRO" --shared-tmp -- /bin/bash -lc "
set -e
$INDEX_ENV
cd /root
if [ -d AstrBot/.git ]; then
  cd AstrBot && git pull --ff-only || echo '  (git pull 失败，用现有代码继续)'
else
  git clone --depth 1 '$ASTRBOT_REPO' AstrBot
  cd AstrBot
fi
uv sync
" || die "装 AstrBot 依赖失败：多半是网络或镜像，重跑一次本脚本即可（已装的部分不会重下）"
ok "AstrBot 依赖装好了"

step "5/5 注册 astr 命令"
if [ -f "$(dirname "$0")/astr" ]; then
  cp "$(dirname "$0")/astr" "$STATE_DIR/astr"
  ok "用同目录下的 astr"
else
  curl -fsSL -o "$STATE_DIR/astr" "$REPO_RAW/termux/astr" || die "下载 astr 失败"
  ok "已下载 astr"
fi
chmod +x "$STATE_DIR/astr"
ln -sf "$STATE_DIR/astr" "$PREFIX/bin/astr"
printf 'DISTRO=%s\nREPO=%s\n' "$DISTRO" "$ASTRBOT_REPO" > "$STATE_DIR/config"
ok "astr 已就位（$PREFIX/bin/astr）"

cat <<'EOT'

装完了。下一步：

  astr start      # 启动 AstrBot（首次启动会初始化 data/）
  astr status     # 看状态和端口
  astr log        # 跟日志
  astr stop

然后浏览器打开 http://127.0.0.1:6185 ，默认账号密码都是 astrbot。
在面板里加「aiocqhttp」平台，端口填 ArkDroid 里那个（默认 6199），token 两边写一样。

ArkDroid 那边点「启动」中继即可，链路是：
  QQ ↔ ArkLink ↔ ArkDroid(ArkBridge) ↔ AstrBot
EOT
