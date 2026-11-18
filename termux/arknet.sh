#!/data/data/com.termux/files/usr/bin/bash
# ArkDroid 的极简 HTTP 客户端：curl 还没装上时的回传兜底。
# 用 bash 自带的 /dev/tcp 发原始 HTTP（Termux bootstrap 必有 bash，未必有 curl）。
#
# 用法：arknet.sh GET  <url>                响应 body 打到 stdout
#       arknet.sh POST <url> [body|-]       body 为 "-" 或缺省时读 stdin
set -u

METHOD="$1"; URL="$2"; BODY="${3:-}"
if [ -z "$BODY" ] || [ "$BODY" = "-" ]; then
  BODY="$(cat)"
fi

REST="${URL#http://}"
HOSTPORT="${REST%%/*}"
PATHX="/${REST#*/}"
HOST="${HOSTPORT%%:*}"
PORT="${HOSTPORT#*:}"
[ "$PORT" = "$HOSTPORT" ] && PORT=80

# Content-Length 必须是字节数：bash 的 ${#BODY} 是字符数，中文会算错
LEN=0
if [ -n "$BODY" ]; then
  LEN=$(printf '%s' "$BODY" | wc -c)
fi

if ! exec 3<>"/dev/tcp/$HOST/$PORT" 2>/dev/null; then
  exit 1
fi
printf '%s %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: arknet/1\r\nContent-Length: %d\r\nConnection: close\r\n\r\n' \
  "$METHOD" "$PATHX" "$HOSTPORT" "$LEN" >&3
if [ -n "$BODY" ]; then
  printf '%s' "$BODY" >&3
fi

# 逐行吃掉 header（bash 内建 read 按字节读，不会多吃），剩下的就是 body
CR=$(printf '\r')
while IFS= read -r line <&3; do
  line="${line%$CR}"
  [ -z "$line" ] && break
done
cat <&3
exec 3<&- 3>&-
