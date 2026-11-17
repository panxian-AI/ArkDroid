#!/usr/bin/env python3
"""下载内核的桌面自测：真起三个 HTTP 服务（慢 / 快 / 404），跑真 Java 代码。

    python tools/selftest_downloader.py            # 用仓库同级的 .tmp/jdk21
    ARKDROID_JDK=/path/to/jdk python tools/...

覆盖：镜像测速择优、断点续传（Range）、校验失败换镜像。
"""
from __future__ import annotations

import hashlib
import http.server
import os
import random
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
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

def run_java(jdk: Path, classes: Path, args: list[str]) -> subprocess.CompletedProcess:
    exe = Path(tool(jdk, "java"))
    return subprocess.run([str(exe), "-cp", str(classes), "ai.panxian.arkdroid.DownloaderCheck", *args],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


class Server:
    """假的镜像：slow 限速、fast 全速、broken 404，都支持 Range。"""

    def __init__(self, mode: str, data: bytes) -> None:
        self.mode = mode
        self.data = data
        self.ranges: list[str] = []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self) -> None:  # noqa: N802
                outer.ranges.append(self.headers.get("Range") or "-")
                if outer.mode == "broken":
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                size = len(outer.data)
                start = 0
                rng = self.headers.get("Range")
                if rng and rng.startswith("bytes="):
                    start = int(rng.split("=", 1)[1].split("-")[0])
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
                else:
                    self.send_response(200)
                self.send_header("Content-Length", str(size - start))
                self.send_header("Accept-Ranges", "bytes")
                self.end_headers()
                body = outer.data[start:]
                step = 32 * 1024
                for i in range(0, len(body), step):
                    try:
                        self.wfile.write(body[i:i + step])
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    if outer.mode == "slow":
                        time.sleep(0.1)  # 约 320 KB/s，测速时明显落后

            def log_message(self, *args) -> None:  # 静音
                return

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/blob.bin"

    def start(self) -> "Server":
        self.thread.start()
        return self

    def stop(self) -> None:
        self.httpd.shutdown()


def main() -> int:
    jdk = find_jdk()
    work = Path(tempfile.mkdtemp(prefix="arkdl-"))
    classes = work / "classes"
    classes.mkdir()

    src = ROOT / "app" / "src" / "main" / "java" / "ai" / "panxian" / "arkdroid" / "Downloader.java"
    chk = ROOT / "tools" / "java" / "ai" / "panxian" / "arkdroid" / "DownloaderCheck.java"
    javac = Path(tool(jdk, "javac"))
    cp = subprocess.run([str(javac), "-encoding", "UTF-8", "-d", str(classes), str(src), str(chk)],
                        capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("下载内核能单独编译（不依赖 Android）", cp.returncode == 0, cp.stderr[-500:])
    if cp.returncode != 0:
        return 1

    random.seed(7)
    blob = bytes(random.getrandbits(8) for _ in range(6 * 1024 * 1024))
    sha = hashlib.sha256(blob).hexdigest()

    slow = Server("slow", blob).start()
    fast = Server("fast", blob).start()
    broken = Server("broken", blob).start()
    try:
        # ---------- 1. 择优：慢的排在快的后面 ----------
        target = work / "a.bin"
        res = run_java(jdk, classes, [str(target), sha, broken.url, slow.url, fast.url])
        out = res.stdout
        chosen = [ln for ln in out.splitlines() if ln.startswith("CHOSEN ")]
        chosen_url = chosen[0][7:] if chosen else ""
        check("下载成功（跳过 404 镜像）", res.returncode == 0 and target.exists(), res.stderr[-400:])
        check("自动选中了最快的镜像", chosen_url == fast.url, f"选了 {chosen_url}，快镜像 {fast.url}")
        check("文件校验和一致", target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == sha)
        check("大小与源一致", target.stat().st_size == len(blob), f"{target.stat().st_size}")

        # ---------- 2. 续传：本地已有 2 MB，应该只补剩下的 ----------
        target2 = work / "b.bin"
        target2.write_bytes(blob[: 2 * 1024 * 1024])
        fast.ranges.clear()
        res2 = run_java(jdk, classes, [str(target2), sha, fast.url])
        ranges = fast.ranges
        check("续传带了 Range 头", any("bytes=2097152-" in r for r in ranges), f"看到 {ranges[:4]}")
        check("续传后文件完整", res2.returncode == 0 and target2.stat().st_size == len(blob), f"{target2.stat().st_size}")
        check("续传后校验和一致",
              hashlib.sha256(target2.read_bytes()).hexdigest() == sha)

        # ---------- 3. 校验和不符：必须换镜像/报错，而不是把坏包留下来 ----------
        target3 = work / "c.bin"
        res3 = run_java(jdk, classes, [str(target3), "0" * 64, fast.url, slow.url])
        check("校验和不对时报错退出", res3.returncode != 0, f"exit={res3.returncode}")
        check("坏包不会被留在原地", not target3.exists() or target3.stat().st_size == 0,
              f"{target3.stat().st_size if target3.exists() else 'absent'}")
    finally:
        for s in (slow, fast, broken):
            s.stop()
        shutil.rmtree(work, ignore_errors=True)

    failed = [n for n, ok, _ in CHECKS if not ok]
    print(f"\n{len(CHECKS) - len(failed)}/{len(CHECKS)} 通过")
    if failed:
        print("失败：" + "；".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
