# 读 AstrBot 面板账号的小脚本（在 proot 的 Ubuntu 里跑）
import json
import os

path = "/root/AstrBot/data/cmd_config.json"
if not os.path.exists(path):
    print("OK=0")
    print("FIRST_RUN=1")
    raise SystemExit(0)
try:
    with open(path, encoding="utf-8") as fh:
        cfg = json.load(fh)
except Exception as exc:  # 配置坏了也别把 App 搞崩
    print("OK=0")
    print("NOTE=" + str(exc))
    raise SystemExit(0)

db = cfg.get("dashboard") or {}
pw = db.get("password") or ""
hashed = bool(db.get("pbkdf2_password"))
print("OK=1")
print("USER=" + str(db.get("username") or "astrbot"))
print("PASS=" + pw)
print("HASHED=" + ("1" if hashed else "0"))
print("FIRST_RUN=" + ("1" if (not hashed and not pw) else "0"))
