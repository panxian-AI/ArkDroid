"""连通性自检：`arkbridge check` 用它，不启动中继也能定位问题出在哪一头。"""

from __future__ import annotations

import asyncio
import itertools
import json
import time
from typing import Any

from .config import BridgeConfig
from .wsclient import HandshakeError, WSClient

_seq = itertools.count(1)


async def probe_arklink(cfg: BridgeConfig, timeout: float = 8.0) -> dict[str, Any]:
    """连 ArkLink → 问账号 → 问在线状态。"""
    out: dict[str, Any] = {
        "ok": False, "self_id": 0, "nickname": "", "online": None, "detail": "",
    }
    try:
        ws = await WSClient.connect(
            cfg.arklink,
            authorization=cfg.arklink_token,
            open_timeout=timeout,
            ping_interval=0,
        )
    except HandshakeError as exc:
        out["detail"] = str(exc)
        return out
    except Exception as exc:
        out["detail"] = f"{exc!r}"
        return out

    async def call(action: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        key = f"arkbridge-probe-{next(_seq)}"
        await ws.send_json({"action": action, "params": params or {}, "echo": key})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            raw = await asyncio.wait_for(ws.recv(), timeout)
            if raw is None:
                raise ConnectionError("ArkLink 在应答前关掉了连接")
            data = json.loads(raw)
            if isinstance(data, dict) and data.get("echo") == key:
                return data
        raise asyncio.TimeoutError(f"{action} 没有应答")

    try:
        login = await call("get_login_info")
        info = login.get("data") or {}
        if login.get("retcode"):
            out["detail"] = f"get_login_info 返回 retcode={login.get('retcode')} {login.get('msg', '')}".strip()
        else:
            out["ok"] = True
            out["self_id"] = int(info.get("user_id") or info.get("uin") or 0)
            out["nickname"] = str(info.get("nickname") or "")
        try:
            status = await call("get_status")
            data = status.get("data") or {}
            out["online"] = bool(data.get("online", data.get("good")))
        except Exception:
            pass
    except asyncio.TimeoutError:
        out["detail"] = f"ArkLink 连上了，但 {timeout:g}s 内不回 get_login_info"
    except Exception as exc:
        out["detail"] = f"{exc!r}"
    finally:
        await ws.close()
    return out


async def probe_astrbot(cfg: BridgeConfig, self_id: int = 0, timeout: float = 8.0) -> dict[str, Any]:
    """按 OneBot 实现的身份向 AstrBot 反向握手。

    会短暂占用一次连接槽，AstrBot 日志里会出现一次「已连接 / 已断开」，正常。
    """
    out: dict[str, Any] = {"ok": False, "detail": ""}
    headers = {"X-Self-ID": str(self_id or 0), "X-Client-Role": "Universal"}
    try:
        ws = await WSClient.connect(
            cfg.astrbot,
            headers=headers,
            authorization=cfg.astrbot_token,
            open_timeout=timeout,
            ping_interval=0,
        )
    except HandshakeError as exc:
        out["detail"] = str(exc)
        return out
    except Exception as exc:
        out["detail"] = f"{exc!r}"
        return out
    out["ok"] = True
    out["detail"] = "握手成功"
    await ws.close(1000, "arkbridge check")
    return out
