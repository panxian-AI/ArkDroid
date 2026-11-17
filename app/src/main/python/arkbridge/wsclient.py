"""WebSocket 客户端（RFC 6455 子集，纯标准库）。

为什么不用 `websockets`：ArkBridge 的定位是「下一个文件就能跑」的中继，
多一个第三方依赖就多一道装不上的墙（尤其是在目标机器上只有个裸 Python 的时候）。
ArkLink 的服务端侧已经这么干了，这里补上对称的另一半。

只实现本项目真正需要的子集，但要求严格到能和 `aiocqhttp` / `aiohttp` /
`websockets 17` 这些真实服务端握手成功：

- 客户端握手（含 `Authorization`、自定义头，如 `X-Self-ID`）
- 掩码帧发送、长度三档编码（7 / 16 / 64 位）
- 分片重组、ping / pong、close 握手
- 心跳与「对端装死」检测（ping 出去后长时间无任何数据就主动断开）
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
import ssl
import struct
import time
from typing import Any, Mapping
from urllib.parse import urlsplit

from . import __version__

logger = logging.getLogger("arkbridge.ws")

_WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
_HEADER_LIMIT = 64 * 1024
_MAX_FRAME = 64 * 1024 * 1024

_OP_CONT, _OP_TEXT, _OP_BIN = 0x0, 0x1, 0x2
_OP_CLOSE, _OP_PING, _OP_PONG = 0x8, 0x9, 0xA


class HandshakeError(RuntimeError):
    """握手被拒（token 不对、路径不对、对方不是 WS 服务端……）。"""

    def __init__(self, message: str, status: int = 0, body: bytes = b""):
        self.status = status
        self.body = body
        super().__init__(message)


class WSClient:
    """一条已握手的客户端连接。用 `WSClient.connect(...)` 创建。"""

    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        url: str,
        *,
        ping_interval: float = 20.0,
        ping_timeout: float = 60.0,
    ) -> None:
        self._reader = reader
        self._writer = writer
        self.url = url
        self.ping_interval = ping_interval
        self.ping_timeout = ping_timeout

        self._closed = False
        self._send_lock = asyncio.Lock()
        self._last_rx = time.monotonic()
        self._frag_opcode = 0
        self._frag_buf = bytearray()
        self._keepalive_task: asyncio.Task[Any] | None = None
        self._close_code = 0

    # ------------------------------------------------------------------ 连接
    @classmethod
    async def connect(
        cls,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        authorization: str = "",
        open_timeout: float = 10.0,
        insecure_tls: bool = False,
        ping_interval: float = 20.0,
        ping_timeout: float = 60.0,
    ) -> "WSClient":
        parts = urlsplit(url)
        if parts.scheme not in ("ws", "wss"):
            raise ValueError(f"不支持的 WebSocket 地址：{url}")
        secure = parts.scheme == "wss"
        host = parts.hostname or "127.0.0.1"
        port = parts.port or (443 if secure else 80)
        target = parts.path or "/"
        if parts.query:
            target += "?" + parts.query

        ssl_ctx: ssl.SSLContext | None = None
        if secure:
            ssl_ctx = ssl.create_default_context()
            if insecure_tls:
                ssl_ctx.check_hostname = False
                ssl_ctx.verify_mode = ssl.CERT_NONE

        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(
                    host,
                    port,
                    ssl=ssl_ctx,
                    server_hostname=host if secure else None,
                    limit=_HEADER_LIMIT,
                ),
                open_timeout,
            )
        except asyncio.TimeoutError:
            raise HandshakeError(f"连不上 {host}:{port}（{open_timeout:g}s 超时）") from None
        except OSError as exc:
            raise HandshakeError(f"连不上 {host}:{port}：{exc}") from None

        key = base64.b64encode(os.urandom(16)).decode()
        is_default_port = port in (80, 443)
        request_headers: dict[str, str] = {
            "Host": host if is_default_port else f"{host}:{port}",
            "Upgrade": "websocket",
            "Connection": "Upgrade",
            "Sec-WebSocket-Key": key,
            "Sec-WebSocket-Version": "13",
            "User-Agent": f"ArkBridge/{__version__}",
        }
        if headers:
            request_headers.update({k: str(v) for k, v in headers.items()})
        if authorization:
            request_headers["Authorization"] = f"Bearer {authorization}"

        lines = [f"GET {target} HTTP/1.1"] + [f"{k}: {v}" for k, v in request_headers.items()]
        raw = ("\r\n".join(lines) + "\r\n\r\n").encode("latin-1")
        try:
            writer.write(raw)
            await asyncio.wait_for(writer.drain(), open_timeout)
            head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), open_timeout)
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError, OSError) as exc:
            await _hard_close(writer)
            raise HandshakeError(f"握手中断：{exc!r}") from None

        text = head.decode("iso-8859-1", "replace")
        head_lines = text.split("\r\n")
        try:
            _proto, code_text, _rest = head_lines[0].split(" ", 2)
            status = int(code_text)
        except ValueError:
            await _hard_close(writer)
            raise HandshakeError(f"对端返回的不是 HTTP 响应：{head_lines[0]!r}") from None

        resp_headers: dict[str, str] = {}
        for line in head_lines[1:]:
            if not line or ":" not in line:
                continue
            k, v = line.split(":", 1)
            resp_headers[k.strip().lower()] = v.strip()

        if status != 101:
            body = b""
            try:
                body = await asyncio.wait_for(reader.read(512), 1.0)
            except Exception:
                pass
            await _hard_close(writer)
            hint = _status_hint(status)
            raise HandshakeError(
                f"{target} 返回 {status}{('（' + hint + '）') if hint else ''}，不是 101 Switching Protocols",
                status=status,
                body=body,
            )

        expect = base64.b64encode(
            hashlib.sha1((key + _WS_GUID).encode()).digest()
        ).decode()
        got = resp_headers.get("sec-websocket-accept", "")
        if got != expect:
            await _hard_close(writer)
            raise HandshakeError(f"Sec-WebSocket-Accept 不匹配（对端不是标准 WS 服务端？）")

        client = cls(reader, writer, url, ping_interval=ping_interval, ping_timeout=ping_timeout)
        if ping_interval > 0:
            client._keepalive_task = asyncio.create_task(
                client._keepalive_loop(), name="arkbridge-ws-keepalive"
            )
        logger.debug("WS 已连接：%s", url)
        return client

    # ------------------------------------------------------------------ 状态
    @property
    def closed(self) -> bool:
        return self._closed or self._writer.is_closing()

    def _mark_closed(self) -> None:
        self._closed = True

    # ------------------------------------------------------------------ 发送
    async def send_text(self, text: str) -> None:
        await self._send_frame(_OP_TEXT, text.encode("utf-8"))

    async def send_json(self, obj: Any) -> None:
        await self.send_text(json.dumps(obj, ensure_ascii=False))

    async def _send_frame(self, opcode: int, payload: bytes) -> None:
        if self.closed:
            raise ConnectionError("WebSocket 已关闭")
        n = len(payload)
        # 客户端发出的帧必须掩码，否则严格实现（aiohttp / websockets）会直接断链
        mask = os.urandom(4)
        if n < 126:
            header = struct.pack("!BB", 0x80 | opcode, 0x80 | n)
        elif n < 1 << 16:
            header = struct.pack("!BBH", 0x80 | opcode, 0x80 | 126, n)
        else:
            header = struct.pack("!BBQ", 0x80 | opcode, 0x80 | 127, n)
        masked = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
        async with self._send_lock:
            try:
                self._writer.write(header + mask + masked)
                await self._writer.drain()
            except (ConnectionError, RuntimeError, OSError):
                self._mark_closed()
                raise

    # ------------------------------------------------------------------ 接收
    async def recv(self) -> str | None:
        """收一条消息（文本或二进制都按 UTF-8 解）。连接关闭返回 None。"""
        while True:
            try:
                fin, opcode, payload = await self._read_frame()
            except (asyncio.IncompleteReadError, ConnectionError, OSError):
                self._mark_closed()
                return None
            self._last_rx = time.monotonic()

            if opcode == _OP_CLOSE:
                if len(payload) >= 2:
                    self._close_code = struct.unpack("!H", payload[:2])[0]
                try:
                    await self._send_frame(_OP_CLOSE, payload[:2])
                except Exception:
                    pass
                self._mark_closed()
                return None
            if opcode == _OP_PING:
                try:
                    await self._send_frame(_OP_PONG, payload)
                except Exception:
                    self._mark_closed()
                    return None
                continue
            if opcode == _OP_PONG:
                continue
            if opcode == _OP_CONT:
                if not self._frag_opcode:
                    continue
                self._frag_buf += payload
                if not fin:
                    continue
                data = bytes(self._frag_buf)
                self._frag_opcode = 0
                self._frag_buf.clear()
                return data.decode("utf-8", "replace")
            if opcode in (_OP_TEXT, _OP_BIN):
                if not fin:
                    self._frag_opcode = opcode
                    self._frag_buf = bytearray(payload)
                    continue
                return payload.decode("utf-8", "replace")
            logger.debug("忽略未知操作码 0x%x", opcode)

    async def _read_frame(self) -> tuple[bool, int, bytes]:
        head = await self._reader.readexactly(2)
        b1, b2 = head[0], head[1]
        fin = bool(b1 & 0x80)
        opcode = b1 & 0x0F
        masked = bool(b2 & 0x80)
        length = b2 & 0x7F
        if length == 126:
            length = struct.unpack("!H", await self._reader.readexactly(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", await self._reader.readexactly(8))[0]
        if length > _MAX_FRAME:
            raise ConnectionError(f"帧过大：{length}")
        mask = await self._reader.readexactly(4) if masked else b""
        payload = await self._reader.readexactly(length) if length else b""
        if masked and payload:
            payload = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
        return fin, opcode, payload

    def __aiter__(self) -> "WSClient":
        return self

    async def __anext__(self) -> str:
        msg = await self.recv()
        if msg is None:
            raise StopAsyncIteration
        return msg

    # ------------------------------------------------------------------ 心跳
    async def _keepalive_loop(self) -> None:
        try:
            while True:
                await asyncio.sleep(self.ping_interval)
                if self.closed:
                    return
                idle = time.monotonic() - self._last_rx
                if idle > self.ping_timeout:
                    # 连接没断但一个字节都没回来：半开连接，占着不放最坑人
                    logger.warning("WS %s 已 %.0fs 无任何数据，判定僵死并断开", self.url, idle)
                    try:
                        await self.close(1001, "心跳超时")
                    except Exception:
                        pass
                    return
                try:
                    await self._send_frame(_OP_PING, b"arkbridge")
                except Exception:
                    self._mark_closed()
                    return
        except asyncio.CancelledError:
            raise

    # ------------------------------------------------------------------ 关闭
    async def close(self, code: int = 1000, reason: str = "") -> None:
        if self._closed:
            return
        self._mark_closed()
        if self._keepalive_task is not None:
            self._keepalive_task.cancel()
            self._keepalive_task = None
        payload = struct.pack("!H", code) + reason.encode("utf-8")[:120]
        try:
            await self._send_frame(_OP_CLOSE, payload)
        except Exception:
            pass
        await _hard_close(self._writer)


async def _hard_close(writer: asyncio.StreamWriter) -> None:
    try:
        writer.close()
        await writer.wait_closed()
    except Exception:
        pass


def _status_hint(status: int) -> str:
    if status == 401:
        return "token 不对，检查两端的 access_token / ws_reverse_token 是否一致"
    if status == 403:
        return "被拒绝，通常是 token 或 X-Self-ID 不被接受"
    if status == 404:
        return "路径不对，AstrBot 反向 WS 的路径是 /ws"
    if status == 426:
        return "对端要求 WebSocket 升级，检查地址是否写成了 http://"
    return ""
