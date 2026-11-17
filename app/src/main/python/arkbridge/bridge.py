"""双向中继核心。

两条连接都是**主动往外连**的，这是整个项目的立足点：

```
        ┌──────── ArkLink（OneBot 11 服务端，等着别人来连）
        │  ws://…/onebot/v11/ws     ← ArkBridge 当客户端连上去：收事件、发 API
   ArkBridge
        │  ws://…/ws                → ArkBridge 当实现连过去：推事件、应答 API
        └──────── AstrBot（aiocqhttp 反向 WS 服务端，等着实现来连）
```

所以要处理的事情只有四类：事件下行、API 上行、echo 配对、两边断线各自重连。
"""

from __future__ import annotations

import asyncio
import itertools
import json
import logging
import random
import time
from collections import deque
from typing import Any

from . import __version__
from .config import BridgeConfig
from .wsclient import HandshakeError, WSClient

logger = logging.getLogger("arkbridge.relay")


class _Backoff:
    """指数退避 + 抖动，避免两端同时重启时一起撞上去。"""

    def __init__(self, cap: float, start: float = 1.0, factor: float = 1.8) -> None:
        self.cap = max(cap, start)
        self.start = start
        self.factor = factor
        self._cur = start

    def next(self) -> float:
        delay = min(self._cur, self.cap)
        self._cur = min(self._cur * self.factor, self.cap)
        return delay * (0.85 + random.random() * 0.3)

    def reset(self) -> None:
        self._cur = self.start


def _failed(retcode: int = 500, msg: str = "") -> dict[str, Any]:
    return {"status": "failed", "retcode": retcode, "data": None, "msg": msg}


class Bridge:
    """一条 ArkLink → AstrBot 的中继链路。"""

    def __init__(self, cfg: BridgeConfig) -> None:
        self.cfg = cfg
        self.log = logging.getLogger(f"arkbridge[{cfg.name}]")
        self.self_id: int = int(cfg.self_id or 0)
        self.stats: dict[str, int] = {
            "events": 0,        # 从 ArkLink 收到的原始事件
            "forwarded": 0,     # 成功推给 AstrBot 的事件
            "dropped": 0,       # 没推出去（AstrBot 未连 + 缓冲已满）
            "buffered": 0,      # 当前缓冲区里的条数（每次读都重算）
            "api_calls": 0,     # AstrBot 发来的 API 调用
            "api_errors": 0,
            "reconnects": 0,
        }

        self._ark: WSClient | None = None
        self._astr: WSClient | None = None
        self._astr_self_id = 0
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._seq = itertools.count(1)
        self._buffer: deque[dict[str, Any]] = deque(maxlen=max(cfg.queue, 0) or 0)
        self._tasks: set[asyncio.Task[Any]] = set()
        self._running = False
        self._stop_evt = asyncio.Event()
        self._identity_warned = False

    # ------------------------------------------------------------------ 生命周期
    async def run(self) -> None:
        self._running = True
        self._stop_evt.clear()
        self.log.info(
            "中继启动 ── ArkLink %s  ⇄  AstrBot %s", self.cfg.arklink, self.cfg.astrbot
        )
        await asyncio.gather(self._arklink_loop(), self._astrbot_loop())

    async def stop(self) -> None:
        if not self._running:
            return
        self._running = False
        self._stop_evt.set()
        self.log.info("中继停止中…（事件 %d 条，API %d 次）", self.stats["events"], self.stats["api_calls"])
        for ws in (self._astr, self._ark):
            if ws is not None:
                try:
                    await ws.close(1001, "ArkBridge 退出")
                except Exception:
                    pass
        for task in list(self._tasks):
            task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks.clear()

    async def _sleep(self, seconds: float) -> None:
        if seconds <= 0:
            return
        try:
            await asyncio.wait_for(self._stop_evt.wait(), seconds)
        except asyncio.TimeoutError:
            pass

    # ------------------------------------------------------------------ ArkLink 侧
    async def _arklink_loop(self) -> None:
        backoff = _Backoff(self.cfg.reconnect_max)
        while self._running:
            try:
                ws = await WSClient.connect(
                    self.cfg.arklink,
                    authorization=self.cfg.arklink_token,
                    open_timeout=10.0,
                    ping_interval=self.cfg.ping_interval,
                    ping_timeout=self.cfg.ping_timeout,
                )
            except HandshakeError as exc:
                delay = backoff.next()
                self.log.warning("连不上 ArkLink：%s；%.0fs 后重试", exc, delay)
                await self._sleep(delay)
                continue
            except Exception as exc:
                delay = backoff.next()
                self.log.warning("连 ArkLink 出错：%r；%.0fs 后重试", exc, delay)
                await self._sleep(delay)
                continue

            backoff.reset()
            self._ark = ws
            self.stats["reconnects"] += 1
            self.log.info("ArkLink 已连接：%s", self.cfg.arklink)
            # 收包必须先跑起来：_identify() 要等 get_login_info 的应答，
            # 而应答正是从这条连接上下来的，串行做会自己把自己等死。
            reader = asyncio.create_task(self._ark_reader(ws), name="arkbridge-ark-reader")
            try:
                await self._identify()
                await reader
            except asyncio.CancelledError:
                raise
            except Exception:
                self.log.exception("处理 ArkLink 数据时异常")
            finally:
                reader.cancel()
                await asyncio.gather(reader, return_exceptions=True)
                self._ark = None
                self._fail_pending("ArkBridge 与 ArkLink 的连接断开了")
                await ws.close()
                if self._running:
                    self.log.warning("ArkLink 连接断开，准备重连")

    async def _ark_reader(self, ws: WSClient) -> None:
        async for raw in ws:
            await self._on_ark_message(raw)

    async def _identify(self) -> None:
        """确认机器人账号。AstrBot 靠 self_id 判断「@ 的是不是我」，不能是 0。"""
        if self.cfg.self_id:
            self.self_id = int(self.cfg.self_id)
            return
        if self.self_id:
            return
        try:
            resp = await self._call_ark("get_login_info", {}, timeout=8.0)
        except Exception as exc:
            self.log.debug("get_login_info 没拿到：%s", exc)
            return
        info = resp.get("data") if isinstance(resp, dict) else None
        uid = (info or {}).get("user_id") or (info or {}).get("uin")
        if uid:
            self._learn_self_id(int(uid))

    def _learn_self_id(self, value: int) -> None:
        if value <= 0 or value == self.self_id:
            return
        old = self.self_id
        self.self_id = value
        if old:
            self.log.info("机器人账号从 %s 变成 %s", old, value)
        else:
            self.log.info("机器人账号：%s", value)
        if self._astr is not None and self._astr_self_id != value:
            # AstrBot 的连接是按 X-Self-ID 注册账号的，换了号必须重连
            self.log.warning("账号变化，重新与 AstrBot 建立连接")
            self._spawn(self._astr.close(1000, "账号变更"), name="arkbridge-reauth")

    async def _on_ark_message(self, raw: str) -> None:
        try:
            data = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            self.log.debug("ArkLink 下发非法 JSON，忽略")
            return
        if not isinstance(data, dict):
            return

        if "post_type" in data:
            await self._relay_event(data)
            return

        echo = data.get("echo")
        if echo is not None:
            fut = self._pending.get(str(echo))
            if fut is not None and not fut.done():
                fut.set_result(data)
            return
        self.log.debug("ArkLink 主动下发（无 echo），忽略：%.120s", raw)

    async def _relay_event(self, data: dict[str, Any]) -> None:
        self.stats["events"] += 1
        event = dict(data)
        event["time"] = int(event.get("time") or time.time())

        sid = event.get("self_id")
        if sid:
            try:
                self._learn_self_id(int(sid))
            except (TypeError, ValueError):
                pass
        if not event.get("self_id"):
            if self.self_id:
                event["self_id"] = self.self_id
            else:
                self.log.debug("事件缺 self_id 且账号未知，%.60s", str(event)[:60])

        ws = self._astr
        if ws is None:
            self._park(event)
            return
        await self._push(ws, event)

    def _park(self, event: dict[str, Any]) -> None:
        """AstrBot 还没连上：消息类事件先存着，元事件存了也没意义。"""
        if event.get("post_type") == "meta_event" or not self.cfg.queue:
            self.stats["dropped"] += 1
            return
        if len(self._buffer) == self._buffer.maxlen:
            self.stats["dropped"] += 1
        self._buffer.append(event)
        self.stats["buffered"] = len(self._buffer)

    async def _push(self, ws: WSClient, event: dict[str, Any]) -> None:
        try:
            await ws.send_json(event)
            self.stats["forwarded"] += 1
        except Exception:
            self._park(event)
            self._astr = None

    async def _flush(self) -> None:
        ws = self._astr
        if ws is None:
            return
        while self._buffer:
            event = self._buffer[0]
            try:
                await ws.send_json(event)
            except Exception:
                self._astr = None
                return
            self._buffer.popleft()
            self.stats["forwarded"] += 1
        self.stats["buffered"] = 0
        self.log.debug("缓冲事件已补发完")

    def _fail_pending(self, reason: str) -> None:
        for echo, fut in list(self._pending.items()):
            if not fut.done():
                fut.set_exception(ConnectionError(reason))
            self._pending.pop(echo, None)

    # ------------------------------------------------------------------ AstrBot 侧
    async def _astrbot_loop(self) -> None:
        backoff = _Backoff(self.cfg.reconnect_max)
        while self._running:
            if not self.self_id:
                if not self._identity_warned:
                    self._identity_warned = True
                    self.log.info("等 ArkLink 报出机器人账号（self_id）后再连 AstrBot；也可以直接在配置里写死 self_id")
                await self._sleep(2.0)
                continue

            headers = {"X-Self-ID": str(self.self_id), "X-Client-Role": "Universal"}
            try:
                ws = await WSClient.connect(
                    self.cfg.astrbot,
                    headers=headers,
                    authorization=self.cfg.astrbot_token,
                    open_timeout=10.0,
                    ping_interval=self.cfg.ping_interval,
                    ping_timeout=self.cfg.ping_timeout,
                )
            except HandshakeError as exc:
                delay = backoff.next()
                self.log.warning("连不上 AstrBot：%s；%.0fs 后重试", exc, delay)
                await self._sleep(delay)
                continue
            except Exception as exc:
                delay = backoff.next()
                self.log.warning("连 AstrBot 出错：%r；%.0fs 后重试", exc, delay)
                await self._sleep(delay)
                continue

            backoff.reset()
            self._astr = ws
            self._astr_self_id = self.self_id
            self.log.info("AstrBot 已连接：%s（self_id=%s）", self.cfg.astrbot, self.self_id)
            try:
                await self._greet(ws)
                await self._flush()
                async for raw in ws:
                    self._on_astr_message(ws, raw)
            except asyncio.CancelledError:
                raise
            except Exception:
                self.log.exception("处理 AstrBot 数据时异常")
            finally:
                if self._astr is ws:
                    self._astr = None
                await ws.close()
                if self._running:
                    self.log.warning("AstrBot 连接断开，准备重连")

    async def _greet(self, ws: WSClient) -> None:
        """补一条 lifecycle.connect：AstrBot 可能是在 ArkLink 之后才连上来的。

        少了它，AstrBot 面板上要等到下一条真实消息才显示在线。
        """
        try:
            await ws.send_json(
                {
                    "time": int(time.time()),
                    "self_id": self.self_id,
                    "post_type": "meta_event",
                    "meta_event_type": "lifecycle",
                    "sub_type": "connect",
                }
            )
        except Exception:
            pass

    def _on_astr_message(self, ws: WSClient, raw: str) -> None:
        try:
            data = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            self.log.debug("AstrBot 下发非法 JSON，忽略")
            return
        if not isinstance(data, dict) or "action" not in data:
            return
        self._spawn(self._serve_action(ws, data), name=f"arkbridge-api-{data.get('action')}")

    async def _serve_action(self, ws: WSClient, data: dict[str, Any]) -> None:
        action = str(data.get("action") or "")
        params = data.get("params")
        if not isinstance(params, dict):
            params = {}
        echo = data.get("echo")
        self.stats["api_calls"] += 1
        started = time.monotonic()

        try:
            resp = await self._call_ark(action, params, timeout=self.cfg.api_timeout)
        except asyncio.TimeoutError:
            self.stats["api_errors"] += 1
            resp = _failed(500, f"{action} 超时（{self.cfg.api_timeout:g}s 内 ArkLink 没回）")
        except ConnectionError as exc:
            self.stats["api_errors"] += 1
            resp = _failed(500, str(exc))
        except Exception as exc:  # 任何意外都不能把 AstrBot 的调用吊死
            self.stats["api_errors"] += 1
            self.log.exception("转发 API %s 失败", action)
            resp = _failed(500, f"ArkBridge 内部错误：{exc}")

        payload = dict(resp)
        payload["echo"] = echo          # 还原 AstrBot 自己的 echo，否则它认不出应答

        elapsed = time.monotonic() - started
        if elapsed > 3.0:
            self.log.warning("API %s 耗时 %.1fs（ArkLink 那边慢）", action, elapsed)

        try:
            await ws.send_json(payload)
        except Exception:
            self.log.debug("AstrBot 连接已断，%s 的应答丢弃", action)
            if self._astr is ws:
                self._astr = None

    async def _call_ark(self, action: str, params: dict[str, Any], timeout: float) -> dict[str, Any]:
        """给 ArkLink 发一条 OneBot API，等它按 echo 回。

        echo 用自家前缀重编，避免和 ArkLink 自己的其它客户端撞号。
        """
        ws = self._ark
        if ws is None:
            raise ConnectionError("ArkBridge 与 ArkLink 的连接还没建立")
        key = f"arkbridge-{next(self._seq)}"
        fut: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        self._pending[key] = fut
        try:
            await ws.send_json({"action": action, "params": params, "echo": key})
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._pending.pop(key, None)

    # ------------------------------------------------------------------ 杂项
    def _spawn(self, coro: Any, name: str) -> None:
        task = asyncio.get_running_loop().create_task(coro, name=name)
        self._tasks.add(task)

        def _done(t: asyncio.Task[Any]) -> None:
            self._tasks.discard(t)
            if not t.cancelled() and t.exception() is not None:
                self.log.debug("后台任务 %s 结束：%r", t.get_name(), t.exception())

        task.add_done_callback(_done)

    def describe(self) -> str:
        return (
            f"[{self.cfg.name}] self_id={self.self_id or '?'} "
            f"ArkLink={'✓' if self._ark else '✗'} AstrBot={'✓' if self._astr else '✗'} "
            f"事件 {self.stats['events']} / 转发 {self.stats['forwarded']} / "
            f"丢弃 {self.stats['dropped']} / API {self.stats['api_calls']}"
        )


__all__ = ["Bridge", "__version__"]
