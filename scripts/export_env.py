#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GeoEnv 加密环境包（.geoenv）导出 / 导入 / 查看脚本。

与应用内 BackupManager 完全同格式（`geoenv-package-v1`）：

    文件布局：
        "GEOPKG1\\n"      8 字节 ASCII 明文头（MAGIC）
        salt              16 字节（随机）
        iv                12 字节（随机）
        ciphertext+tag    AES-256-GCM 密文（128-bit tag 附尾）

    密钥派生：PBKDF2WithHmacSHA256(passphrase, salt, 210000) -> 256-bit AES key
    AAD     ：b"geoenv-pkg:" + salt        （防止密文被拼接到别的包）
    明文    ：ZIP（manifest.json / environments.json / accounts.json /
                   snapshots.json / proxies.json / hosts.json / groups.json /
                   theme.json [/ audit.json] [/ profiles/env_<id>/...]）

用法：
    # 导出：把一个目录（内含上述 JSON）打成加密包
    export_env.py pack <dir> <out.geoenv> --pass <口令>

    # 导入/解包：解密到目录（供人工检查或再导入应用）
    export_env.py unpack <in.geoenv> <dir> --pass <口令>

    # 查看 manifest 摘要
    export_env.py info <in.geoenv> --pass <口令>

    # 仅校验文件头 / 格式（不需口令）
    export_env.py check <in.geoenv>

依赖：Python 3.8+，**零第三方依赖**（PBKDF2 用 hashlib，AES-GCM 自带实现）。
     若环境里装有 `cryptography` 或 `pycryptodome`，会自动优先使用（快数倍）。
"""

import argparse
import hashlib
import io
import json
import os
import sys
import zipfile

MAGIC = b"GEOPKG1\n"
AAD_PREFIX = b"geoenv-pkg:"
SALT_LEN = 16
IV_LEN = 12
TAG_BITS = 128
ITERATIONS = 210_000
KEY_BITS = 256
FORMAT = "geoenv-package-v1"

# ZIP 内顶层条目（与 BackupManager.putJson 调用顺序一致）
JSON_ENTRIES = [
    "manifest.json",
    "environments.json",
    "accounts.json",
    "snapshots.json",
    "proxies.json",
    "hosts.json",
    "groups.json",
    "theme.json",
    "audit.json",          # 可选
    "profile_index.json",  # 可选
]


# =========================================================================== #
#  AES-256-GCM：三个后端（cryptography > pycryptodome > 内置纯 Python 实现）
# =========================================================================== #
def _backend() -> str:
    try:
        import cryptography  # noqa: F401
        return "cryptography"
    except ImportError:
        pass
    try:
        import Crypto  # noqa: F401
        return "pycryptodome"
    except ImportError:
        pass
    return "builtin"


def _aes_gcm_encrypt(key: bytes, iv: bytes, aad: bytes, plaintext: bytes) -> bytes:
    """返回 ciphertext || tag（与 JCA `doFinal` 输出一致）。"""
    b = _backend()
    if b == "cryptography":
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM(key).encrypt(iv, plaintext, aad)
    if b == "pycryptodome":
        from Crypto.Cipher import AES
        c = AES.new(key, AES.MODE_GCM, nonce=iv, mac_len=16)
        c.update(aad)
        ct, tag = c.encrypt_and_digest(plaintext)
        return ct + tag
    return _builtin_gcm_crypt(key, iv, aad, encrypt=True, data=plaintext)


def _aes_gcm_decrypt(key: bytes, iv: bytes, aad: bytes, data: bytes) -> bytes:
    b = _backend()
    if b == "cryptography":
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM(key).decrypt(iv, data, aad)
    if b == "pycryptodome":
        from Crypto.Cipher import AES
        if len(data) < 16:
            raise ValueError("密文过短")
        ct, tag = data[:-16], data[-16:]
        c = AES.new(key, AES.MODE_GCM, nonce=iv, mac_len=16)
        c.update(aad)
        return c.decrypt_and_verify(ct, tag)
    try:
        return _builtin_gcm_crypt(key, iv, aad, encrypt=False, data=data)
    except ValueError as exc:
        raise SystemExit(f"错误：解密失败——口令错误或包已损坏（{exc}）")


# ---------------------------- 内置纯 Python AES-GCM ------------------------- #
def _build_sbox() -> list:
    """由 GF(2^8) 逆元 + 仿射变换生成 AES S-box（避免硬编码 256 字节表出错）。"""
    def gmul(a: int, b: int) -> int:
        p = 0
        for _ in range(8):
            if b & 1:
                p ^= a
            hi = a & 0x80
            a = (a << 1) & 0xFF
            if hi:
                a ^= 0x1B
            b >>= 1
        return p

    sb = []
    for a in range(256):
        inv = 0
        if a:
            for cand in range(1, 256):
                if gmul(a, cand) == 1:
                    inv = cand
                    break
        r = 0
        for i in range(8):
            bit = (
                ((inv >> i) ^ (inv >> ((i + 4) % 8)) ^ (inv >> ((i + 5) % 8)) ^
                 (inv >> ((i + 6) % 8)) ^ (inv >> ((i + 7) % 8)) ^ (0x63 >> i))
                & 1
            )
            r |= bit << i
        sb.append(r)
    return sb


_SBOX = _build_sbox()


def _xtime(a: int) -> int:
    return ((a << 1) ^ 0x1B) & 0xFF if (a & 0x80) else (a << 1)


def _expand_key(key: bytes) -> list:
    """AES-256 密钥扩展（Nk=8, Nr=14）→ 60 个 4 字节 word。"""
    nk, nr = 8, 14
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    rcon = 1
    for i in range(nk, 4 * (nr + 1)):
        t = w[i - 1][:]
        if i % nk == 0:
            t = t[1:] + t[:1]
            t = [_SBOX[b] for b in t]
            t[0] ^= rcon
            rcon = _xtime(rcon)
        elif i % nk == 4:
            t = [_SBOX[b] for b in t]
        w.append([w[i - nk][j] ^ t[j] for j in range(4)])
    return w


def _add_round_key(s: list, w: list, rnd: int) -> None:
    # state 行主序：index = 4*row + col；round key 第 col 个 word 的第 row 字节
    for col in range(4):
        rk = w[4 * rnd + col]
        for row in range(4):
            s[4 * row + col] ^= rk[row]


def _shift_rows(s: list) -> None:
    orig = s[:]
    for row in range(1, 4):
        for col in range(4):
            s[4 * row + col] = orig[4 * row + ((col + row) % 4)]


def _mix_columns(s: list) -> None:
    for col in range(4):
        a0, a1, a2, a3 = (s[4 * 0 + col], s[4 * 1 + col],
                          s[4 * 2 + col], s[4 * 3 + col])

        def mul(x: int, n: int) -> int:
            # GF(2^8) 乘常数（倍增法）
            r = 0
            while n:
                if n & 1:
                    r ^= x
                x = _xtime(x)
                n >>= 1
            return r

        s[4 * 0 + col] = mul(a0, 2) ^ mul(a1, 3) ^ a2 ^ a3
        s[4 * 1 + col] = a0 ^ mul(a1, 2) ^ mul(a2, 3) ^ a3
        s[4 * 2 + col] = a0 ^ a1 ^ mul(a2, 2) ^ mul(a3, 3)
        s[4 * 3 + col] = mul(a0, 3) ^ a1 ^ a2 ^ mul(a3, 2)


def _aes256_encrypt_block(w: list, block: bytes) -> bytes:
    # FIPS-197：state[r][c] = in[r + 4c]，本实现行主序 flat = 4r+c，
    # 故输入需按 (i%4, i//4) 转置摆入（该映合是对合，输出用同一映射还原）。
    s = [0] * 16
    for i in range(16):
        s[4 * (i % 4) + (i // 4)] = block[i]
    _add_round_key(s, w, 0)
    for rnd in range(1, 14):
        for i in range(16):
            s[i] = _SBOX[s[i]]
        _shift_rows(s)
        _mix_columns(s)
        _add_round_key(s, w, rnd)
    for i in range(16):
        s[i] = _SBOX[s[i]]
    _shift_rows(s)
    _add_round_key(s, w, 14)
    out = [0] * 16
    for i in range(16):
        out[i] = s[4 * (i % 4) + (i // 4)]
    return bytes(out)


def _gf128_mul(x: int, y: int) -> int:
    """GF(2^128) 乘法（GCM 约化多项式 0xE1||0^120）。"""
    z = 0
    for i in range(128):
        if (y >> (127 - i)) & 1:
            z ^= x
        if x & 1:
            x = (x >> 1) ^ 0xE1000000000000000000000000000000
        else:
            x >>= 1
    return z


def _ghash(h: int, aad: bytes, ct: bytes) -> int:
    y = 0

    def absorb(data: bytes) -> None:
        nonlocal y
        for i in range(0, len(data), 16):
            blk = data[i:i + 16]
            if len(blk) < 16:
                blk = blk + b"\x00" * (16 - len(blk))
            y = _gf128_mul(y ^ int.from_bytes(blk, "big"), h)

    absorb(aad)
    absorb(ct)
    lens = (len(aad) * 8).to_bytes(8, "big") + (len(ct) * 8).to_bytes(8, "big")
    y = _gf128_mul(y ^ int.from_bytes(lens, "big"), h)
    return y


def _gctr(w: list, cb: bytes, data: bytes) -> bytes:
    out = bytearray()
    ctr = bytearray(cb)
    for i in range(0, len(data), 16):
        ks = _aes256_encrypt_block(w, bytes(ctr))
        chunk = data[i:i + 16]
        out.extend(b ^ k for b, k in zip(chunk, ks))
        for j in range(15, 11, -1):          # inc32
            ctr[j] = (ctr[j] + 1) & 0xFF
            if ctr[j] != 0:
                break
    return bytes(out)


def _builtin_gcm_crypt(key: bytes, iv: bytes, aad: bytes,
                       encrypt: bool, data: bytes) -> bytes:
    """纯 Python AES-256-GCM。encrypt=True → ct||tag；False → 校验并返回明文。"""
    if len(key) != 32:
        raise ValueError("仅支持 AES-256")
    if len(iv) != 12:
        raise ValueError("仅支持 96-bit IV")

    w = _expand_key(key)
    h = int.from_bytes(_aes256_encrypt_block(w, b"\x00" * 16), "big")
    j0 = iv + b"\x00\x00\x00\x01"

    if encrypt:
        # 输入为明文：GCTR 得密文，GHASH 必须对**密文**计算
        out = _gctr(w, _gctr_counter_next(j0), data)
        s = _ghash(h, aad, out)
        tag = bytes(a ^ b for a, b in zip(
            _aes256_encrypt_block(w, j0), s.to_bytes(16, "big")))
        return out + tag

    # 解密：输入为密文||tag，GHASH 对**密文**（去 tag）计算，再 GCTR 还原明文
    if len(data) < 16:
        raise ValueError("密文过短")
    ct_in, tag_in = data[:-16], data[-16:]
    s = _ghash(h, aad, ct_in)
    tag = bytes(a ^ b for a, b in zip(
        _aes256_encrypt_block(w, j0), s.to_bytes(16, "big")))
    if not _ct_equal(tag, tag_in):
        raise ValueError("认证标签不匹配")
    return _gctr(w, _gctr_counter_next(j0), ct_in)


def _gctr_counter_next(cb: bytes) -> bytes:
    b = bytearray(cb)
    for j in range(15, 11, -1):
        b[j] = (b[j] + 1) & 0xFF
        if b[j] != 0:
            break
    return bytes(b)


def _ct_equal(a: bytes, b: bytes) -> bool:
    if len(a) != len(b):
        return False
    r = 0
    for x, y in zip(a, b):
        r |= x ^ y
    return r == 0


# --------------------------------------------------------------------------- #
#  密钥派生 / 封包
# --------------------------------------------------------------------------- #
def _derive_key(passphrase: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac(
        "sha256", passphrase.encode("utf-8"), salt, ITERATIONS, dklen=KEY_BITS // 8
    )


def seal(plaintext: bytes, passphrase: str) -> bytes:
    salt = os.urandom(SALT_LEN)
    iv = os.urandom(IV_LEN)
    key = _derive_key(passphrase, salt)
    ct = _aes_gcm_encrypt(key, iv, AAD_PREFIX + salt, plaintext)
    return MAGIC + salt + iv + ct


def open_pkg(data: bytes, passphrase: str) -> bytes:
    head = len(MAGIC) + SALT_LEN + IV_LEN + TAG_BITS // 8
    if len(data) <= head:
        raise SystemExit("错误：包文件损坏或过小")
    if data[: len(MAGIC)] != MAGIC:
        raise SystemExit("错误：不是 GeoEnv 环境包（文件头不匹配）")
    salt = data[len(MAGIC): len(MAGIC) + SALT_LEN]
    iv = data[len(MAGIC) + SALT_LEN: len(MAGIC) + SALT_LEN + IV_LEN]
    ct = data[len(MAGIC) + SALT_LEN + IV_LEN:]
    key = _derive_key(passphrase, salt)
    return _aes_gcm_decrypt(key, iv, AAD_PREFIX + salt, ct)


# --------------------------------------------------------------------------- #
#  子命令
# --------------------------------------------------------------------------- #
def cmd_pack(args: argparse.Namespace) -> int:
    src = args.dir
    if not os.path.isdir(src):
        raise SystemExit(f"错误：目录不存在：{src}")

    present = [e for e in JSON_ENTRIES if os.path.isfile(os.path.join(src, e))]
    if "manifest.json" not in present:
        raise SystemExit("错误：目录内缺少 manifest.json（先导出/准备 manifest）")
    if "environments.json" not in present:
        raise SystemExit("错误：目录内缺少 environments.json")

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in JSON_ENTRIES:                    # 1) JSON（固定顺序）
            p = os.path.join(src, name)
            if os.path.isfile(p):
                zf.write(p, name)
        prof = os.path.join(src, "profiles")          # 2) profiles/（可选）
        if os.path.isdir(prof):
            for root, _dirs, files in os.walk(prof):
                for f in files:
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, src).replace(os.sep, "/")
                    zf.write(full, rel)

    sealed = seal(buf.getvalue(), args.pass_)
    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(sealed)

    with zipfile.ZipFile(io.BytesIO(buf.getvalue())) as zf:
        names = zf.namelist()

    print(f"导出完成：{args.out}")
    print(f"  大小      : {len(sealed)} 字节")
    print(f"  条目数    : {len(names)}")
    print(f"  格式      : {FORMAT}（AES-256-GCM / PBKDF2-HMAC-SHA256 x{ITERATIONS}）")
    print(f"  crypto    : {_backend()}")
    print(f"  sha256    : {_sha256(sealed)}")
    print("  口令      : （导入时必须一致）")
    return 0


def cmd_unpack(args: argparse.Namespace) -> int:
    with open(args.in_, "rb") as fh:
        data = fh.read()
    plain = open_pkg(data, args.pass_)
    os.makedirs(args.dir, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(plain)) as zf:
        for info in zf.infolist():                    # 防路径穿越
            name = info.filename
            if name.startswith("/") or ".." in name.split("/"):
                raise SystemExit(f"错误：包内含非法路径：{name}")
        zf.extractall(args.dir)
        names = zf.namelist()

    print(f"解包完成：{args.dir}")
    print(f"  条目数 : {len(names)}")
    for n in sorted(names)[:40]:
        print(f"    - {n}")
    if len(names) > 40:
        print(f"    … 共 {len(names)} 项")
    print()
    print("提示：把该目录交给 pack 可重新打包；或在应用内「设置 → 导入环境包」"
          "使用原 .geoenv 文件导入。")
    return 0


def cmd_info(args: argparse.Namespace) -> int:
    with open(args.in_, "rb") as fh:
        data = fh.read()
    plain = open_pkg(data, args.pass_)
    with zipfile.ZipFile(io.BytesIO(plain)) as zf:
        names = zf.namelist()
        try:
            manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        except KeyError:
            manifest = {}

        def count(n):
            try:
                v = json.loads(zf.read(n).decode("utf-8"))
                return len(v) if isinstance(v, list) else 1
            except Exception:
                return 0

        envs = []
        if "environments.json" in names:
            envs = json.loads(zf.read("environments.json").decode("utf-8"))

    print(f"包文件    : {args.in_}")
    print(f"大小      : {len(data)} 字节")
    print(f"格式      : {manifest.get('format', FORMAT)}")
    print(f"应用版本  : {manifest.get('appName', 'GeoEnv')} {manifest.get('appVersion', '?')}")
    print(f"Gecko     : {manifest.get('geckoVersion', '?')}")
    created = manifest.get("createdAt")
    if created:
        import datetime
        print(f"创建时间  : "
              f"{datetime.datetime.fromtimestamp(created / 1000).isoformat()}")
    print(f"环境      : {manifest.get('envCount', count('environments.json'))} 个")
    print(f"账号      : {manifest.get('accountCount', count('accounts.json'))} 条")
    print(f"快照      : {manifest.get('snapshotCount', count('snapshots.json'))} 条")
    print(f"代理      : {manifest.get('proxyCount', count('proxies.json'))} 条")
    print(f"含 profile: {'是' if manifest.get('includeProfiles') else '否'}"
          f"（登录态/缓存，跨设备导入可恢复）")
    print(f"含审计    : {'是' if manifest.get('includeAudit') else '否'}")
    print(f"ZIP 条目  : {len(names)}")
    if envs:
        print("环境列表  :")
        for e in envs[:20]:
            print(f"    - {e.get('name')} (id={e.get('id')}, proxy={e.get('proxyId')})")
        if len(envs) > 20:
            print(f"    … 共 {len(envs)} 个")
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    with open(args.in_, "rb") as fh:
        data = fh.read()
    ok = True
    if data[: len(MAGIC)] != MAGIC:
        print("文件头    : 不匹配（不是 GeoEnv 环境包）")
        ok = False
    else:
        print("文件头    : GEOPKG1 ✓")
    min_len = len(MAGIC) + SALT_LEN + IV_LEN + TAG_BITS // 8
    if len(data) <= min_len:
        print("长度      : 过小（损坏）")
        ok = False
    else:
        print(f"长度      : {len(data)} 字节 ✓")
        print(f"salt/iv   : {SALT_LEN}/{IV_LEN} 字节 ✓")
    print(f"加密      : AES-256-GCM（tag 128-bit），密钥 = "
          f"PBKDF2-HMAC-SHA256 x{ITERATIONS} ✓")
    print(f"sha256    : {_sha256(data)}")
    if not ok:
        return 1
    print(f"\n格式校验通过（crypto={_backend()}）。"
          "用 `info --pass <口令>` 验证解密。")
    return 0


def _sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


# --------------------------------------------------------------------------- #
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="GeoEnv 加密环境包（.geoenv）工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="与应用内 BackupManager 完全兼容：GEOPKG1 + AES-256-GCM + PBKDF2。",
    )
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("pack", help="目录 → 加密环境包")
    p.add_argument("dir", help="包含 manifest.json / environments.json … 的目录")
    p.add_argument("out", help="输出 .geoenv 文件路径")
    p.add_argument("--pass", dest="pass_", required=True, help="导出口令（>=8 位）")
    p.set_defaults(func=cmd_pack)

    p = sub.add_parser("unpack", help="加密环境包 → 目录")
    p.add_argument("in_", metavar="in", help="输入 .geoenv 文件")
    p.add_argument("dir", help="输出目录")
    p.add_argument("--pass", dest="pass_", required=True, help="导入口令（须与导出一致）")
    p.set_defaults(func=cmd_unpack)

    p = sub.add_parser("info", help="查看包摘要")
    p.add_argument("in_", metavar="in", help="输入 .geoenv 文件")
    p.add_argument("--pass", dest="pass_", required=True, help="口令")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("check", help="仅校验文件格式（不需口令）")
    p.add_argument("in_", metavar="in", help="输入 .geoenv 文件")
    p.set_defaults(func=cmd_check)

    args = ap.parse_args(argv)
    if getattr(args, "pass_", None) is not None and len(args.pass_) < 8:
        raise SystemExit("错误：口令至少 8 位（与应用内校验一致）")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
