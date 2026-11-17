#!/usr/bin/env python3
"""把 ArkBridge 的 `arkbridge` 包同步进 App。

App 里的 Python 代码只应该有一份来源（ArkBridge 仓库），否则两边迟早漂移。
CI 每次构建都跑一遍；本地改了 ArkBridge 也可以手动跑。

    python tools/sync_arkbridge.py                      # 拉 main
    python tools/sync_arkbridge.py --ref v0.1.0         # 拉指定 tag
    python tools/sync_arkbridge.py --local ../ArkBridge # 从本地 checkout 拷
"""

from __future__ import annotations

import argparse
import io
import json
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "app" / "src" / "main" / "python" / "arkbridge"
RECORD = ROOT / "app" / "src" / "main" / "python" / "ARKBRIDGE_VERSION"


def from_local(path: Path) -> tuple[str, str]:
    source = path / "arkbridge"
    if not source.is_dir():
        raise SystemExit(f"{source} 不存在，--local 要指向 ArkBridge 仓库根目录")
    version = "unknown"
    init = source / "__init__.py"
    if init.is_file():
        for line in init.read_text(encoding="utf-8").splitlines():
            if line.startswith("__version__"):
                version = line.split("=", 1)[1].strip().strip("\"'")
                break
    return str(source), version


def fetch(ref: str) -> tuple[bytes, str]:
    url = f"https://github.com/panxian-AI/ArkBridge/archive/refs/heads/{ref}.tar.gz"
    if ref.startswith("v") or ref[0].isdigit():
        url = f"https://github.com/panxian-AI/ArkBridge/archive/refs/tags/{ref}.tar.gz"

    request = urllib.request.Request(url, headers={"User-Agent": "arkdroid-sync"})
    with urllib.request.urlopen(request, timeout=90) as response:
        blob = response.read()

    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
        members = [m for m in tar.getmembers() if "/arkbridge/" in m.name and m.isfile()]
        if not members:
            raise SystemExit("压缩包里没有 arkbridge/，是不是 ref 写错了？")
        version = "unknown"
        for member in members:
            if member.name.endswith("/arkbridge/__init__.py"):
                text = tar.extractfile(member).read().decode("utf-8")
                for line in text.splitlines():
                    if line.startswith("__version__"):
                        version = line.split("=", 1)[1].strip().strip("\"'")
    return blob, version


def extract_into(blob: bytes) -> None:
    if TARGET.exists():
        shutil.rmtree(TARGET)
    TARGET.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
        for member in tar.getmembers():
            if not member.isfile() or "/arkbridge/" not in member.name:
                continue
            name = member.name.split("/arkbridge/", 1)[1]
            if not name.endswith(".py"):
                continue
            data = tar.extractfile(member).read()
            (TARGET / name).write_bytes(data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", default="main", help="ArkBridge 的分支或 tag")
    parser.add_argument("--local", type=Path, help="从本地 ArkBridge 目录拷，不走网络")
    args = parser.parse_args()

    if args.local:
        source, version = from_local(args.local)
        if TARGET.exists():
            shutil.rmtree(TARGET)
        shutil.copytree(source, TARGET)
        ref = f"local:{args.local}"
    else:
        blob, version = fetch(args.ref)
        extract_into(blob)
        ref = args.ref

    files = sorted(p.name for p in TARGET.glob("*.py"))
    RECORD.write_text(
        json.dumps({"ref": ref, "version": version, "files": files}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"arkbridge {version} ← {ref}")
    print("  " + ", ".join(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
