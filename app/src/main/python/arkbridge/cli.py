"""命令行入口：`arkbridge run / setup / check / init`。

两个目标：
1. **不用配置文件也能跑** —— `python -m arkbridge run` 直接吃默认地址（6700 / 6199），
   临时换机器就加 `--arklink/--astrbot` 两个参数，不写文件。
2. **出错时告诉用户下一步改哪里** —— 每条失败信息都带具体地址和该去哪个界面改。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path
from typing import Sequence

from . import __version__
from .bridge import Bridge
from .config import DEFAULT_CONFIG_TEXT, AppConfig, BridgeConfig, ConfigError, load_config
from .probe import probe_arklink, probe_astrbot

LOG_FORMAT = "%(asctime)s %(levelname)-7s %(name)s | %(message)s"


def _setup_logging(level: str, verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else getattr(logging, level.upper(), logging.INFO),
        format=LOG_FORMAT,
        datefmt="%H:%M:%S",
    )
    logging.getLogger("arkbridge.ws").setLevel(logging.DEBUG if verbose else logging.INFO)


def _connection_args() -> argparse.ArgumentParser:
    """run / check 共用的一组「临时改地址」参数（不落盘）。"""
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--arklink", help="ArkLink 地址，如 127.0.0.1:6700")
    parser.add_argument("--astrbot", help="AstrBot 反向 WS 地址，如 127.0.0.1:6199")
    parser.add_argument("--self-id", type=int, default=None, help="机器人 QQ；不给就自动识别")
    parser.add_argument("--arklink-token", help="ArkLink 的 access_token")
    parser.add_argument("--astrbot-token", help="AstrBot 的 ws_reverse_token")
    return parser


def build_parser() -> argparse.ArgumentParser:
    conn = _connection_args()
    parser = argparse.ArgumentParser(
        prog="arkbridge",
        description="把 ArkLink 的 OneBot 11 中继给 AstrBot（或任何只会当反向 WS 服务端的框架）",
        epilog="零依赖：没装也能跑，`python -m arkbridge run` 即可。",
    )
    parser.add_argument("-c", "--config", default=None, help="配置文件路径（默认 ./bridge.toml）")
    parser.add_argument("-v", "--verbose", action="store_true", help="打印调试日志")
    parser.add_argument("--version", action="version", version=f"arkbridge {__version__}")
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("run", parents=[conn], help="启动中继（前台运行）").set_defaults(func=_cmd_run)
    sub.add_parser("setup", parents=[], help="交互式生成 bridge.toml，并打印 AstrBot 侧该填什么").set_defaults(
        func=_cmd_setup
    )
    check = sub.add_parser("check", parents=[conn], help="自检：分别探一下 ArkLink 和 AstrBot 通不通")
    check.set_defaults(func=_cmd_check)

    init = sub.add_parser("init", help="生成一份 bridge.toml 模板")
    init.add_argument("path", nargs="?", default="bridge.toml")
    init.add_argument("--force", action="store_true", help="覆盖已存在的文件")
    init.set_defaults(func=_cmd_init)
    return parser


# ---------------------------------------------------------------- 公共小工具
def _apply_overrides(config: AppConfig, args: argparse.Namespace) -> AppConfig:
    first = config.bridges[0]
    changed = False
    for flag, attr in (
        ("arklink", "arklink"),
        ("astrbot", "astrbot"),
        ("arklink_token", "arklink_token"),
        ("astrbot_token", "astrbot_token"),
    ):
        value = getattr(args, flag, None)
        if value:
            setattr(first, attr, value)
            changed = True
    if getattr(args, "self_id", None):
        first.self_id = int(args.self_id)
        changed = True
    if changed:
        config.validate()
    return config


def _banner(config: AppConfig) -> None:
    print(f"ArkBridge {__version__}｜配置：{config.source}")
    for cfg in config.bridges:
        sid = str(cfg.self_id) if cfg.self_id else "自动识别"
        print(f"  [{cfg.name}] ArkLink {cfg.arklink}")
        print(f"  [{cfg.name}] AstrBot {cfg.astrbot}   self_id={sid}")
    print("  Ctrl-C 退出；自检用 `python -m arkbridge check`")
    print()


def _next_steps(cfg: BridgeConfig) -> None:
    host, _, port = cfg.arklink.partition("://")[2].partition("/")[0].partition(":")
    port = port or "6700"
    astr_port = cfg.astrbot.rsplit(":", 1)[-1].split("/")[0] if ":" in cfg.astrbot else "6199"
    token_part = f" --token {cfg.arklink_token}" if cfg.arklink_token else ""
    print(
        "\n接下来三步：\n"
        f"  1) 起 ArkLink 的 OneBot 服务（另开一个窗口）：\n"
        f"       python -m arklink serve onebot --type ntqq-hook --port {port}{token_part}\n"
        f"  2) AstrBot WebUI → 配置 → 消息平台 → 新增 aiocqhttp：\n"
        f"       ws_reverse_host  = {host or '127.0.0.1'}\n"
        f"       ws_reverse_port  = {astr_port}\n"
        f"       ws_reverse_token = {cfg.astrbot_token or '（留空，或自己定一个，两边一致）'}\n"
        f"  3) 自检 + 启动：\n"
        f"       python -m arkbridge check\n"
        f"       python -m arkbridge run\n"
    )


def _ask(label: str, default: str) -> str:
    try:
        answer = input(f"{label} [{default}]: ").strip()
    except EOFError:
        answer = ""
    return answer or default


# ---------------------------------------------------------------- 子命令
async def _run_all(config: AppConfig) -> int:
    bridges = [Bridge(cfg) for cfg in config.bridges]
    tasks = [asyncio.create_task(b.run(), name=f"arkbridge-{b.cfg.name}") for b in bridges]
    try:
        await asyncio.gather(*tasks)
    except asyncio.CancelledError:
        raise
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*(b.stop() for b in bridges), return_exceptions=True)
    return 0


def _cmd_run(args: argparse.Namespace) -> int:
    config = _apply_overrides(load_config(args.config), args)
    _setup_logging(config.log_level, args.verbose)
    _banner(config)
    try:
        asyncio.run(_run_all(config))
    except KeyboardInterrupt:
        print("\n已退出（Ctrl-C）")
    return 0


def _cmd_check(args: argparse.Namespace) -> int:
    base = load_config(args.config)
    _apply_overrides(base, args)
    _setup_logging("WARNING", args.verbose)
    failures = 0
    for idx, cfg in enumerate(base.bridges, 1):
        print(f"\n[{idx}/{len(base.bridges)}] 链路 {cfg.name}")
        print(f"      ArkLink  {cfg.arklink}")
        ark = asyncio.run(probe_arklink(cfg))
        self_id = int(args.self_id or cfg.self_id or ark.get("self_id") or 0)
        if ark["ok"]:
            extra = f"self_id={ark['self_id']}"
            if ark.get("nickname"):
                extra += f" nickname={ark['nickname']}"
            if ark.get("online") is not None:
                extra += f" online={ark['online']}"
            print(f"      ✓ {extra}")
        else:
            failures += 1
            print(f"      ✗ {ark['detail']}")
            print("        → 确认 ArkLink 起了 OneBot 服务：python -m arklink serve onebot --port 6700")

        print(f"      AstrBot  {cfg.astrbot}")
        astr = asyncio.run(probe_astrbot(cfg, self_id=self_id))
        if astr["ok"]:
            print(f"      ✓ {astr['detail']}（X-Self-ID: {self_id or '0'}）")
        else:
            failures += 1
            print(f"      ✗ {astr['detail']}")
            print("        → AstrBot 里要加 aiocqhttp 平台，反向 WS 端口与路径 /ws 与上面一致")

    print()
    if failures:
        print(f"自检未通过：{failures} 项失败")
        return 1
    print("自检通过：两头都通，可以 `python -m arkbridge run` 了")
    return 0


def _cmd_setup(args: argparse.Namespace) -> int:
    if not sys.stdin.isatty():
        print(
            "当前不是交互终端，改用参数直接跑：\n"
            "  python -m arkbridge run --arklink 127.0.0.1:6700 --astrbot 127.0.0.1:6199"
        )
        return 2

    print("回车用括号里的默认值；全部默认就是「ArkLink 6700 + AstrBot 6199 都在本机」。\n")
    arklink = _ask("ArkLink OneBot 地址", "ws://127.0.0.1:6700/onebot/v11/ws")
    astrbot = _ask("AstrBot 反向 WS 地址", "ws://127.0.0.1:6199/ws")
    self_id_raw = _ask("机器人 QQ（留空=自动识别）", "")
    ark_token = _ask("ArkLink token（没设就留空）", "")
    astr_token = _ask("AstrBot ws_reverse_token（没设就留空）", "")

    lines = [
        "# ArkBridge 配置（arkbridge setup 生成）",
        "",
        "[[bridge]]",
        'name = "main"',
        f"arklink = {json.dumps(arklink, ensure_ascii=False)}",
        f"astrbot = {json.dumps(astrbot, ensure_ascii=False)}",
    ]
    if self_id_raw.isdigit():
        lines.append(f"self_id = {int(self_id_raw)}")
    if ark_token:
        lines.append(f"arklink_token = {json.dumps(ark_token, ensure_ascii=False)}")
    if astr_token:
        lines.append(f"astrbot_token = {json.dumps(astr_token, ensure_ascii=False)}")
    lines += ["", "[log]", 'level = "INFO"', ""]

    path = Path(args.config or "bridge.toml")
    cfg = BridgeConfig(
        arklink=arklink,
        astrbot=astrbot,
        self_id=int(self_id_raw) if self_id_raw.isdigit() else 0,
        arklink_token=ark_token,
        astrbot_token=astr_token,
    ).normalized()
    cfg.validate()

    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n已写入 {path}")
    _next_steps(cfg)
    return 0


def _cmd_init(args: argparse.Namespace) -> int:
    path = Path(args.path)
    if path.exists() and not args.force:
        print(f"{path} 已存在；要覆盖请加 --force")
        return 1
    path.write_text(DEFAULT_CONFIG_TEXT, encoding="utf-8")
    print(f"已写入 {path}")
    _next_steps(BridgeConfig().normalized())
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        parser.print_help()
        return 0
    try:
        return int(args.func(args))
    except ConfigError as exc:
        print(f"配置有问题：{exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
