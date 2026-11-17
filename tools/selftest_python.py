#!/usr/bin/env python3
"""在桌面上验证 App 内嵌的 Python 层（arkdroid.py + arkbridge）。

不碰 Android：只走「配置 → 启动 → 状态 → 日志 → 停止 → 自检」这条链，
挡住真正致命的问题（导入路径、TOML 字段、状态 JSON 形状、线程与事件循环）。

    python tools/selftest_python.py
"""

from __future__ import annotations

import json
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY_DIR = ROOT / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PY_DIR))

import arkdroid  # noqa: E402

CHECKS: list[tuple[str, bool, str]] = []

# arkdroid 会把 sys.stdout 接到 App 的日志面板上，这里留住真控制台
OUT = sys.stdout


def check(name: str, ok: bool, detail: str = "") -> None:
    CHECKS.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"   [{detail}]"), file=OUT)


def write_config(directory: Path) -> Path:
    cfg = directory / "bridge.toml"
    cfg.write_text(
        '[[bridge]]\n'
        'name = "main"\n'
        'arklink = "ws://127.0.0.1:1/onebot/v11/ws"\n'
        'astrbot = "ws://127.0.0.1:2/ws"\n'
        'self_id = 0\n'
        'api_timeout = 2\n'
        'reconnect_max = 1\n'
        'queue = 5\n'
        '\n[log]\nlevel = "INFO"\n',
        encoding="utf-8",
    )
    return cfg


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        cfg = write_config(Path(tmp))

        message = arkdroid.start(str(cfg))
        check("start() 返回启动成功", "中继已启动" in message, message)

        time.sleep(0.8)
        state = json.loads(arkdroid.stats())
        check("stats() 是合法 JSON 且 running=true", state.get("running") is True, arkdroid.stats()[:200])
        check(
            "stats() 带出了 bridge 信息",
            len(state.get("bridges", [])) == 1 and state["bridges"][0]["name"] == "main",
            json.dumps(state, ensure_ascii=False)[:200],
        )
        stats = state["bridges"][0].get("stats", {}) if state.get("bridges") else {}
        check(
            "stats 里有完整计数器",
            {"events", "forwarded", "api_calls", "reconnects"} <= set(stats),
            str(stats),
        )

        logs = arkdroid.drain()
        check("drain() 拿到了运行日志", len(logs) > 0, repr(logs[:120]))
        check("日志里有中继启动字样", "中继启动" in logs, repr(logs[:200]))
        check("drain() 取过就清空", arkdroid.drain() == "", "第二次还有内容")

        arkdroid.stop()
        deadline = time.time() + 6
        stopped = False
        while time.time() < deadline:
            if json.loads(arkdroid.stats()).get("running") is False:
                stopped = True
                break
            time.sleep(0.2)
        check("stop() 之后 running=false", stopped, arkdroid.stats()[:200])

        # 配置写坏了要原样把原因说出来，而不是抛异常
        broken = Path(tmp) / "broken.toml"
        broken.write_text('[[bridge]]\nname = "x"\narklink = ""\nastrbot = "ws://127.0.0.1:2/ws"\n', encoding="utf-8")
        bad = arkdroid.start(str(broken))
        check("坏配置给出人话错误", "启动失败" in bad, bad)
        check("坏配置后 stats 带 error", json.loads(arkdroid.stats()).get("error") != "", arkdroid.stats()[:200])

        # 两个端口都是关的，自检要如实说「不通」而不是卡死
        started = time.time()
        probe = arkdroid.probe(str(cfg))
        cost = time.time() - started
        check("probe() 在 25s 内返回", cost < 25, f"{cost:.1f}s")
        check("probe() 报告 ArkLink 不通", "✗ ArkLink 不通" in probe, probe[:200])
        check("probe() 报告 AstrBot 不通", "✗ AstrBot 不通" in probe, probe[:300])

    failed = [name for name, ok, _ in CHECKS if not ok]
    print(f"\n{len(CHECKS) - len(failed)}/{len(CHECKS)} 通过", file=OUT)
    if failed:
        print("失败：" + "；".join(failed), file=OUT)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
