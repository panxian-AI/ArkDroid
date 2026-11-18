# 读 AstrBot 面板账号的小脚本（在 proot 的 Ubuntu 里跑）
#
# AstrBot 4.30+ 把密码存成 pbkdf2/md5 哈希，配置里读不出明文。但 ArkDroid 负责
# 重置密码时会把自己设的明文记在 /root/.arkdroid/panel.pw（App 和 Ubuntu 共享的
# 状态目录）。这里把这个记录拿出来，并用配置里的哈希**验证**它还是不是当前密码
# ——面板里改过密码的话记录就失效了，验证能兜住这种情况。
import hashlib
import json
import os
import re

CFG = "/root/AstrBot/data/cmd_config.json"
KNOWN = "/root/.arkdroid/panel.pw"
MD5_RE = re.compile(r"^[0-9a-fA-F]{32}$")


def emit(ok, **kv):
    print("OK=" + ("1" if ok else "0"))
    for k, v in kv.items():
        print("%s=%s" % (k, v))
    raise SystemExit(0)


def verify(pw, db):
    """pw 是不是当前密码？对 pbkdf2 / md5 两种存法都验。"""
    if not pw:
        return False
    pb = str(db.get("pbkdf2_password") or "")
    if pb.startswith("pbkdf2_sha256$"):
        try:
            _, iters, salt, digest = pb.split("$")
            calc = hashlib.pbkdf2_hmac(
                "sha256", pw.encode("utf-8"), bytes.fromhex(salt), int(iters)
            ).hex()
            return calc == digest
        except Exception:
            return False
    stored = str(db.get("password") or "")
    if MD5_RE.match(stored):
        return hashlib.md5(pw.encode("utf-8")).hexdigest() == stored
    return stored == pw  # 极老的版本明文存


known = ""
if os.path.exists(KNOWN):
    try:
        with open(KNOWN, encoding="utf-8") as fh:
            known = fh.read().strip()
    except Exception:
        known = ""

if not os.path.exists(CFG):
    emit(False, FIRST_RUN="1", KNOWN="0")

try:
    # utf-8-sig：AstrBot 的 cmd_config.json 可能带 BOM，裸 utf-8 会解析失败
    with open(CFG, encoding="utf-8-sig") as fh:
        cfg = json.load(fh)
except Exception as exc:
    emit(False, NOTE="配置读不了: %s" % exc, KNOWN="0")

db = cfg.get("dashboard") or {}
user = str(db.get("username") or "astrbot")
stored = str(db.get("password") or "")
pbkdf2 = str(db.get("pbkdf2_password") or "")
# password 字段是 32 位十六进制 = md5 哈希，不是明文；别把它当密码显示（那正是
# 「照着卡片输密码却提示密码错误」的原因）
hashed = bool(pbkdf2) or bool(MD5_RE.match(stored))
chg = bool(db.get("password_change_required"))

if known and verify(known, db):
    emit(True, USER=user, PASS=known, KNOWN="1", HASHED="0",
         CHG="1" if chg else "0")
if not hashed:
    # 真明文（旧版或没设过）：直接给
    emit(True, USER=user, PASS=stored, KNOWN="0",
         HASHED="0", CHG="1" if chg else "0",
         FIRST_RUN="1" if not stored else "0")

# 哈希存着、App 又没有可用的明文记录：显示不出来，但用户可以点「重置面板密码」
if known:
    emit(True, USER=user, PASS="", KNOWN="0", HASHED="1",
         CHG="1" if chg else "0", NOTE="记录的旧密码已失效（面板里改过）")
emit(True, USER=user, PASS="", KNOWN="0", HASHED="1",
     CHG="1" if chg else "0")
