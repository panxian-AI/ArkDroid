#!/usr/bin/env python3
"""用真 curl 打 HttpWire：验证 Termux → App 的三种 POST 形态都能收全。

    python tools/selftest_http.py
"""
from __future__ import annotations

import hashlib
import os
import random
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECKS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    CHECKS.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"   [{detail}]"))


EXE = ".exe" if os.name == "nt" else ""


def tool(jdk: Path, name: str) -> str:
    """Windows 下叫 javac.exe，Linux 下就叫 javac —— CI 在 ubuntu 上跑。"""
    return str(jdk / "bin" / (name + EXE))


def find_jdk() -> Path:
    env = os.environ.get("ARKDROID_JDK")
    if env and Path(tool(Path(env), "javac")).exists():
        return Path(env)
    for cand in (ROOT.parent / ".tmp" / "jdk21").glob("jdk-*"):
        if Path(tool(cand, "javac")).exists():
            return cand
    home = os.environ.get("JAVA_HOME")
    if home and Path(tool(Path(home), "javac")).exists():
        return Path(home)
    found = shutil.which("javac")
    if found:
        return Path(found).resolve().parent.parent
    raise SystemExit("找不到带 javac 的 JDK：设 ARKDROID_JDK 指过去")

def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def sha_prefix(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


def main() -> int:
    jdk = find_jdk()
    work = Path(tempfile.mkdtemp(prefix="arkhttp-"))
    classes = work / "classes"
    classes.mkdir()
    src = ROOT / "app" / "src" / "main" / "java" / "ai" / "panxian" / "arkdroid"

    cp = subprocess.run(
        [str(Path(tool(jdk, "javac"))), "-encoding", "UTF-8", "-d", str(classes),
         str(src / "HttpWire.java"),
         str(ROOT / "tools" / "java" / "ai" / "panxian" / "arkdroid" / "HttpWireCheck.java")],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("HttpWire 能单独编译", cp.returncode == 0, cp.stderr[-400:])
    if cp.returncode != 0:
        return 1

    port = free_port()
    proc = subprocess.Popen([str(Path(tool(jdk, "java"))), "-cp", str(classes),
                             "ai.panxian.arkdroid.HttpWireCheck", str(port), "3"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace")
    assert proc.stdout is not None
    ready = proc.stdout.readline().strip()
    if not ready.startswith("READY"):
        print("服务端没起来：", ready, proc.stderr.read() if proc.stderr else "")
        return 1

    base = f"http://127.0.0.1:{port}/t/abc/p/term"
    try:
        # ---------- 1. 管道 → chunked（Termux 里 astr xxx | curl --data-binary @- ） ----------
        payload1 = ("第一行输出\n" * 50).encode()
        # 和 Termux 里那条管道等价：stdin 是管道，curl 只能用 chunked
        r1 = subprocess.run(["curl", "-sS", "-m", "20", "-X", "POST", "--data-binary", "@-", base],
                            input=payload1, capture_output=True, timeout=40)
        line1 = proc.stdout.readline().strip()

        # ---------- 2. 文件 → Content-Length ----------
        blob = bytes(random.getrandbits(8) for _ in range(300 * 1024))
        f2 = work / "body.bin"
        f2.write_bytes(blob)
        r2 = subprocess.run(["curl", "-sS", "-m", "30", "-X", "POST", "--data-binary", f"@{f2}", base],
                            capture_output=True, timeout=60)
        line2 = proc.stdout.readline().strip()

        # ---------- 3. 2 MB → 会带 Expect: 100-continue ----------
        big = bytes(random.getrandbits(8) for _ in range(2 * 1024 * 1024))
        f3 = work / "big.bin"
        f3.write_bytes(big)
        t0 = time.monotonic()
        r3 = subprocess.run(["curl", "-sS", "-m", "30", "-X", "POST", "--data-binary", f"@{f3}", base],
                            capture_output=True, timeout=90)
        line3 = proc.stdout.readline().strip()
        elapsed3 = time.monotonic() - t0
    finally:
        proc.wait(timeout=30)
        shutil.rmtree(work, ignore_errors=True)

    check("管道上传的 body 收全", f"got={len(payload1)}" in line1 and sha_prefix(payload1) in line1, line1)
    check("Content-Length body 收全", f"got={len(blob)}" in line2 and sha_prefix(blob) in line2, line2)
    check("Content-Length 被正确识别", "chunked=false" in line2 and f"declared={len(blob)}" in line2, line2)
    check("2 MB body 收全（Expect 没把 curl 卡死）",
          f"got={len(big)}" in line3 and sha_prefix(big) in line3 and elapsed3 < 25,
          f"{line3} 耗时 {elapsed3:.1f}s")
    check("curl 每次都能拿到 200", r1.stdout.strip() == b"OK" and r2.stdout.strip() == b"OK"
          and r3.stdout.strip() == b"OK",
          f"{r1.stdout!r} {r2.stdout!r} {r3.stdout!r}")

    # ---------- 4. chunked 解析（curl 不会流式发，只能手工构造报文） ----------
    jdk_java = Path(tool(jdk, "java"))
    work2 = Path(tempfile.mkdtemp(prefix="arkchunk-"))
    classes2 = work2 / "classes"
    classes2.mkdir()
    subprocess.run([str(Path(tool(jdk, "javac"))), "-encoding", "UTF-8", "-d", str(classes2),
                    str(src / "HttpWire.java"),
                    str(ROOT / "tools" / "java" / "ai" / "panxian" / "arkdroid" / "HttpWireCheck.java")],
                   capture_output=True)
    ch = subprocess.run([str(jdk_java), "-cp", str(classes2),
                         "ai.panxian.arkdroid.HttpWireCheck", "--chunked"],
                        capture_output=True, text=True, encoding="utf-8", errors="replace")
    shutil.rmtree(work2, ignore_errors=True)
    out = ch.stdout
    got = expect = -1
    for ln in out.splitlines():
        if ln.startswith("BODY "):
            parts = dict(p.split("=") for p in ln.split()[1:])
            got, expect = int(parts["got"]), int(parts["expect"])
    check("chunked 多段拼接正确", "MATCH true" in out and got == expect and expect > 10000,
          f"{out.splitlines()[:3]} stderr={ch.stderr[-200:]}")
    check("chunked 被正确识别", "chunked=true" in out, out[:200])
    check("chunked 被截断时不死循环/不抛异常", "TRUNCATED got=" in out, out[-200:])

    failed = [n for n, ok, _ in CHECKS if not ok]
    print(f"\n{len(CHECKS) - len(failed)}/{len(CHECKS)} 通过")
    if failed:
        print("失败：" + "；".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
