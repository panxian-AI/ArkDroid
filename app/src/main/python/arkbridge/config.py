"""配置：`bridge.toml` 解析 + 环境变量覆盖 + 地址简写。

想让人一条命令就跑起来，所以配置只做三件事：给默认值、认简写、把错误说人话。
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit, urlunsplit

DEFAULT_ARKLINK = "ws://127.0.0.1:6700/onebot/v11/ws"
DEFAULT_ASTRBOT = "ws://127.0.0.1:6199/ws"

#: 各家的默认 WS 路径（写简写时自动补）
_DEFAULT_PATHS = {
    "arklink": "/onebot/v11/ws",
    "astrbot": "/ws",
}

_SCHEME_ALIAS = {
    "astrbot": "ws",
    "arklink": "ws",
    "http": "ws",
    "https": "wss",
}


class ConfigError(ValueError):
    """配置写错了（消息会直接打印给用户，所以要说人话）。"""


@dataclass(slots=True)
class BridgeConfig:
    name: str = "main"
    arklink: str = DEFAULT_ARKLINK
    astrbot: str = DEFAULT_ASTRBOT
    #: 机器人 QQ 号；0 = 连上 ArkLink 后自己问出来
    self_id: int = 0
    arklink_token: str = ""
    astrbot_token: str = ""
    #: 单条 API 的超时（秒）。AstrBot 默认 60s，这里给同样的量级
    api_timeout: float = 60.0
    #: AstrBot 断线期间最多缓存多少条事件（0 = 不缓存）
    queue: int = 200
    #: 心跳间隔与僵死判定，秒；0 = 关掉
    ping_interval: float = 20.0
    ping_timeout: float = 60.0
    #: 重连退避上限，秒
    reconnect_max: float = 60.0

    def normalized(self) -> "BridgeConfig":
        self.arklink = normalize_url(self.arklink, "arklink")
        self.astrbot = normalize_url(self.astrbot, "astrbot")
        self.name = (self.name or "main").strip() or "main"
        return self

    def validate(self) -> None:
        if self.api_timeout <= 0:
            raise ConfigError(f"[bridge.{self.name}] api_timeout 必须大于 0")
        if self.queue < 0:
            raise ConfigError(f"[bridge.{self.name}] queue 不能是负数（0 表示不缓存）")
        for label, url in (("arklink", self.arklink), ("astrbot", self.astrbot)):
            parts = urlsplit(url)
            if parts.scheme not in ("ws", "wss"):
                raise ConfigError(f"[bridge.{self.name}] {label} 不是 ws/wss 地址：{url}")
            if not parts.hostname:
                raise ConfigError(f"[bridge.{self.name}] {label} 缺少主机名：{url}")


@dataclass(slots=True)
class AppConfig:
    bridges: list[BridgeConfig] = field(default_factory=list)
    log_level: str = "INFO"
    source: str = ""          # 配置文件路径，仅用于日志

    def validate(self) -> None:
        if not self.bridges:
            raise ConfigError("没有配置任何 [[bridge]]，至少要有一条")
        seen: set[str] = set()
        for cfg in self.bridges:
            cfg.normalized().validate()
            if cfg.name in seen:
                raise ConfigError(f"bridge 名字重复：{cfg.name}")
            seen.add(cfg.name)


def normalize_url(url: str, kind: str) -> str:
    """把 `127.0.0.1:6199` / `astrbot://host:port` / `http://...` 统一成 ws 地址。"""
    url = (url or "").strip()
    if not url:
        raise ConfigError(f"{kind} 地址是空的")
    if "://" not in url:
        url = "ws://" + url
    parts = urlsplit(url)
    # `astrbot://` / `arklink://` 除了换协议，还顺便带上自家的默认路径
    kind = parts.scheme if parts.scheme in _DEFAULT_PATHS else kind
    scheme = _SCHEME_ALIAS.get(parts.scheme, parts.scheme)
    if scheme not in ("ws", "wss"):
        raise ConfigError(f"{kind} 地址协议不认识：{parts.scheme}（只认 ws/wss）")
    path = parts.path
    if not path or path == "/":
        path = _DEFAULT_PATHS.get(kind, "/")
    return urlunsplit((scheme, parts.netloc, path, parts.query, ""))


DEFAULT_CONFIG_TEXT = """\
# ArkBridge 配置：把 ArkLink 的 OneBot 11 中继给 AstrBot（或任何只会当反向 WS 服务端的框架）
#
# 链路：QQ ↔ ArkLink ↗ ArkBridge ↘ AstrBot
#                    （两头都是 ArkBridge 主动连出去）

[[bridge]]
name = "main"
# ArkLink 的 OneBot 11 服务端（`arklink serve onebot` 的地址，短写也行：127.0.0.1:6700）
arklink = "ws://127.0.0.1:6700/onebot/v11/ws"
# AstrBot 的 aiocqhttp 反向 WS 地址（astrbot://127.0.0.1:6199 会自动补 /ws）
astrbot = "ws://127.0.0.1:6199/ws"

# 机器人 QQ 号。留 0 或删掉都行：ArkBridge 会连上 ArkLink 问 get_login_info 自己学。
# 但 AstrBot 靠它判断「@ 的是不是我」，学不到就填这里。
# self_id = 10001

# ArkLink 的 access_token（没设就留空）
# arklink_token = ""
# AstrBot 里 aiocqhttp 平台的 ws_reverse_token，两边必须一致
# astrbot_token = ""

# api_timeout = 60      # 单条 API 超时（秒）
# queue = 200           # AstrBot 断开期间缓存多少条事件
# ping_interval = 20    # 心跳间隔（秒），0 = 关闭
# ping_timeout = 60     # 多久收不到任何数据就判定僵死
# reconnect_max = 60    # 重连退避上限（秒）

[log]
level = "INFO"
"""


def load_config(path: str | Path | None = None) -> AppConfig:
    """读配置。`path=None` 时依次找 `bridge.toml`、`arkbridge.toml`，都没有就用默认值 + 环境变量。"""
    candidates: list[Path] = []
    if path:
        candidates = [Path(path)]
    else:
        for name in ("bridge.toml", "arkbridge.toml"):
            if Path(name).exists():
                candidates = [Path(name)]
                break

    if candidates:
        target = candidates[0]
        if not target.exists():
            raise ConfigError(f"配置文件不存在：{target}")
        try:
            with target.open("rb") as fh:
                data = tomllib.load(fh)
        except tomllib.TOMLDecodeError as exc:
            raise ConfigError(f"{target} 不是合法的 TOML：{exc}") from None
        config = _from_dict(data, source=str(target))
    else:
        config = AppConfig(bridges=[BridgeConfig()], source="（默认值）")

    _apply_env(config)
    config.validate()
    return config


def _from_dict(data: dict[str, Any], source: str) -> AppConfig:
    log_section = data.get("log") or {}
    log_level = str(log_section.get("level", "INFO")).upper()

    raw_bridges: list[dict[str, Any]] = []
    if isinstance(data.get("bridge"), list):
        raw_bridges = [b for b in data["bridge"] if isinstance(b, dict)]
    elif isinstance(data.get("bridge"), dict):
        raw_bridges = [data["bridge"]]
    elif isinstance(data.get("bridge"), str):        # 容错：bridge = "url"
        raw_bridges = [{"arklink": data["bridge"]}]

    if not raw_bridges:
        raw_bridges = [{}]

    bridges = [_bridge_from(raw) for raw in raw_bridges]
    return AppConfig(bridges=bridges, log_level=log_level, source=source)


_FIELDS = {
    "name": ("name", str),
    "arklink": ("arklink", str),
    "astrbot": ("astrbot", str),
    "self_id": ("self_id", int),
    "arklink_token": ("arklink_token", str),
    "astrbot_token": ("astrbot_token", str),
    "api_timeout": ("api_timeout", float),
    "queue": ("queue", int),
    "ping_interval": ("ping_interval", float),
    "ping_timeout": ("ping_timeout", float),
    "reconnect_max": ("reconnect_max", float),
}


def _bridge_from(raw: dict[str, Any]) -> BridgeConfig:
    unknown = set(raw) - set(_FIELDS)
    if unknown:
        raise ConfigError(f"[[bridge]] 里有不认识的键：{', '.join(sorted(unknown))}")
    kwargs: dict[str, Any] = {}
    for key, (attr, caster) in _FIELDS.items():
        if key not in raw:
            continue
        value = raw[key]
        try:
            kwargs[attr] = caster(value)
        except (TypeError, ValueError):
            raise ConfigError(f"[[bridge]] {key} 的值类型不对：{value!r}") from None
    return BridgeConfig(**kwargs)


def _apply_env(config: AppConfig) -> None:
    """环境变量覆盖第一条 bridge —— 容器里不方便挂配置文件时的逃生口。"""
    if not config.bridges:
        return
    first = config.bridges[0]
    mapping = {
        "ARKBRIDGE_ARKLINK": ("arklink", str),
        "ARKBRIDGE_ASTRBOT": ("astrbot", str),
        "ARKBRIDGE_SELF_ID": ("self_id", int),
        "ARKBRIDGE_ARKLINK_TOKEN": ("arklink_token", str),
        "ARKBRIDGE_ASTRBOT_TOKEN": ("astrbot_token", str),
        "ARKBRIDGE_API_TIMEOUT": ("api_timeout", float),
        "ARKBRIDGE_LOG_LEVEL": ("__log__", str),
    }
    for env, (attr, caster) in mapping.items():
        value = os.environ.get(env)
        if value is None or value == "":
            continue
        if attr == "__log__":
            config.log_level = value.upper()
            continue
        try:
            setattr(first, attr, caster(value))
        except ValueError:
            raise ConfigError(f"环境变量 {env}={value!r} 类型不对") from None
