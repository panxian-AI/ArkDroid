# 把 AstrBot 面板密码改成指定值（在 proot 的 Ubuntu 里跑）
#
# 用法：python3 setpw.py <新密码>
# 写法和 AstrBot 自己的 set_dashboard_password_hashes 完全一致：
#   dashboard.pbkdf2_password = pbkdf2_sha256$600000$<salt_hex>$<digest_hex>
#   dashboard.password        = md5(new)   （AstrBot 留作旧版兼容的字段）
#   dashboard.password_change_required = False（不然登录后会卡在强制改密页）
#   dashboard.password_storage_upgraded = True
import hashlib
import json
import secrets
import sys

CFG = "/root/AstrBot/data/cmd_config.json"

if len(sys.argv) != 2 or not sys.argv[1]:
    print("ERR=用法: setpw.py <新密码>")
    raise SystemExit(2)
pw = sys.argv[1]

salt_hex = secrets.token_hex(16)  # 16 字节随机盐，和 AstrBot 的做法一致
digest = hashlib.pbkdf2_hmac(
    "sha256", pw.encode("utf-8"), bytes.fromhex(salt_hex), 600_000
).hex()

try:
    # utf-8-sig：cmd_config.json 可能带 BOM，裸 utf-8 会解析失败
    with open(CFG, encoding="utf-8-sig") as fh:
        cfg = json.load(fh)
except Exception as exc:
    print("ERR=配置读不了: %s" % exc)
    raise SystemExit(1)

db = cfg.setdefault("dashboard", {})
db["pbkdf2_password"] = "pbkdf2_sha256$600000$%s$%s" % (salt_hex, digest)
db["password"] = hashlib.md5(pw.encode("utf-8")).hexdigest()
db["password_storage_upgraded"] = True
db["password_change_required"] = False

with open(CFG, "w", encoding="utf-8") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)

print("OK=1")
print("USER=" + str(db.get("username") or "astrbot"))
print("PASS=" + pw)
