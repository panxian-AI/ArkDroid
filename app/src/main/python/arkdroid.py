"""ArkDroid 的 Python 入口：在 App 进程里托管 ArkBridge 中继。

Java 侧（BridgeService）通过 Chaquopy 调这几个函数：

    start(config_path) -> str   起中继，返回给人看的一句话
    stop() -> str               停中继
    stats() -> str              JSON：running / error / 每条桥的实时数字
    drain() -> str              取走累积的日志行
    probe(config_path) -> str   连通性自检（连 ArkLink 问账号 + 和 AstrBot 握手）

设计上刻意只依赖标准库 + 同目录下的 arkbridge 包，不碰 pip：
Android 上没有 manylinux 轮子，任何第三方依赖都会变成装不上。
"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
import threading
import time
import traceback
from collections import deque

_LOG: deque[str] = deque(maxlen=1500)
_LOG_LOCK = threading.Lock()
_STATE_LOCK = threading.Lock()
_BRIDGE_LOCK = threading.Lock()

_STATE = {"running": False, "error": ""}
_BRIDGES: list = []
_LOOP: asyncio.AbstractEventLoop | None = None
_THREAD: threading.Thread | None = None
_ORIGINAL_STDOUT = sys.stdout


# --------------------------------------------------------------------- 日志


def _log(message: object) -> None:
    """写一行到面板缓冲（时间戳由 Java 侧再加一层，这里只留原文）。"""
    text = str(message).rstrip("\n")
    if not text.strip():
        return
    with _LOG_LOCK:
        for line in text.split("\n"):
            if line.strip():
                _LOG.append(line)


class _QueueHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        try:
            _log(self.format(record))
        except Exception:  # noqa: BLE001 - 日志本身不该抛
            pass


class _Writer:
    """把 print / 未捕获输出也导到面板里。"""

    def __init__(self, prefix: str = "") -> None:
        self.prefix = prefix

    def write(self, data: object) -> int:
        text = "" if data is None else str(data)
        for line in text.rstrip("\n").split("\n"):
            if line.strip():
                _log(self.prefix + line)
        return len(text)

    def flush(self) -> None:
        return None

    def isatty(self) -> bool:
        return False


def _install_logging(level: str = "INFO") -> None:
    root = logging.getLogger()
    root.setLevel(getattr(logging, str(level).upper(), logging.INFO))
    for handler in list(root.handlers):
        root.removeHandler(handler)
    handler = _QueueHandler()
    handler.setFormatter(logging.Formatter("%(name)s: %(message)s"))
    root.addHandler(handler)
    if not isinstance(sys.stdout, _Writer):
        sys.stdout = _Writer()
        sys.stderr = _Writer("[stderr] ")


def drain() -> str:
    """取走自上次调用以来的所有日志。"""
    with _LOG_LOCK:
        if not _LOG:
            return ""
        lines = list(_LOG)
        _LOG.clear()
    return "\n".join(lines)


def _say(message: str) -> str:
    _log(message)
    return message


def _set_state(**kwargs: object) -> None:
    with _STATE_LOCK:
        _STATE.update({key: value for key, value in kwargs.items() if key in _STATE})


# --------------------------------------------------------------------- 生命周期


def start(config_path: str) -> str:
    """起中继。会先停掉上一份，保证幂等。"""
    global _THREAD

    stop()
    _install_logging()

    try:
        from arkbridge.bridge import Bridge
        from arkbridge.config import load_config

        config = load_config(config_path)
        config.validate()
    except Exception as exc:  # noqa: BLE001 - 配置错要原样告诉用户
        message = f"启动失败：{exc}"
        _set_state(running=False, error=str(exc))
        return _say(message)

    bridges = [Bridge(item) for item in config.bridges]
    with _BRIDGE_LOCK:
        _BRIDGES[:] = bridges
    _set_state(running=False, error="")

    _THREAD = threading.Thread(target=_run_loop, args=(bridges,), name="arkbridge", daemon=True)
    _THREAD.start()

    detail = "，".join(f"{b.cfg.name}: {b.cfg.arklink} → {b.cfg.astrbot}" for b in bridges)
    return _say(f"中继已启动（{len(bridges)} 条链路）{detail}")


def _run_loop(bridges: list) -> None:
    global _LOOP

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _LOOP = loop
    _set_state(running=True, error="")

    try:
        loop.run_until_complete(asyncio.gather(*[bridge.run() for bridge in bridges]))
    except asyncio.CancelledError:
        pass
    except Exception as exc:  # noqa: BLE001
        _log("中继异常退出：\n" + traceback.format_exc())
        _set_state(error=str(exc))
    finally:
        _set_state(running=False)
        try:
            loop.close()
        except Exception:  # noqa: BLE001
            pass
        _LOOP = None
        _log("中继已停止")


def stop() -> str:
    loop = _LOOP
    with _BRIDGE_LOCK:
        bridges = list(_BRIDGES)

    if loop is None or not loop.is_running():
        _set_state(running=False)
        return "中继没在跑"

    for bridge in bridges:
        try:
            asyncio.run_coroutine_threadsafe(bridge.stop(), loop)
        except Exception:  # noqa: BLE001
            pass
    return _say("正在停止中继…")


def stats() -> str:
    """给 Java 轮询的实时状态。"""
    with _STATE_LOCK:
        out = {"running": _STATE["running"], "error": _STATE["error"], "bridges": []}
    with _BRIDGE_LOCK:
        bridges = list(_BRIDGES)

    for bridge in bridges:
        try:
            out["bridges"].append({
                "name": bridge.cfg.name,
                "self_id": int(getattr(bridge, "self_id", 0) or 0),
                "arklink": bridge.cfg.arklink,
                "astrbot": bridge.cfg.astrbot,
                "stats": dict(bridge.stats),
            })
        except Exception:  # noqa: BLE001
            continue
    return json.dumps(out, ensure_ascii=False)


# --------------------------------------------------------------------- 自检


def probe(config_path: str) -> str:
    """分别探两头：ArkLink 能不能连、账号是谁；AstrBot 接不接受握手。"""
    _install_logging()

    try:
        from arkbridge.config import load_config

        config = load_config(config_path)
        config.validate()
        item = config.bridges[0]
    except Exception as exc:  # noqa: BLE001
        return _say(f"自检失败：{exc}")

    async def run_probe():
        from arkbridge.probe import probe_arklink, probe_astrbot

        ark = await probe_arklink(item)
        self_id = int(item.self_id or ark.get("self_id") or 0)
        astr = await probe_astrbot(item, self_id=self_id)
        return ark, astr, self_id

    try:
        ark, astr, self_id = asyncio.run(run_probe())
    except Exception as exc:  # noqa: BLE001
        return _say(f"自检失败：{exc}")

    lines: list[str] = []
    if ark.get("ok"):
        nickname = ark.get("nickname") or "(没昵称)"
        lines.append(f"✓ ArkLink 通了：账号 {ark.get('self_id')} {nickname}，在线={ark.get('online')}")
    else:
        lines.append(f"✗ ArkLink 不通：{ark.get('detail') or '原因不明'}")

    if astr.get("ok"):
        lines.append("✓ AstrBot 握手成功（它会记一次连接/断开，正常）")
    else:
        lines.append(f"✗ AstrBot 不通：{astr.get('detail') or '原因不明'}")

    if ark.get("ok") and not item.self_id:
        lines.append(f"提示：QQ 号可以不填，启动时会自己学（现在学到 {ark.get('self_id')}）")
    if not astr.get("ok"):
        lines.append("排查：AstrBot 面板里要加一个 aiocqhttp 平台，端口和上面一致，token 两边相同")
    if not ark.get("ok"):
        lines.append("排查：ArkLink 那边要起 OneBot 服务（arklink serve onebot），端口和上面一致")

    return _say("\n".join(lines))
