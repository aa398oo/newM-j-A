"""用 Python 交叉验证 Kotlin 侧 ForgeInstaller 的解析/替换逻辑。

**它验证的是什么**：`ForgeInstaller` 里那套「data 坐标只算路径、args 里 [coord] 才下载、
sides=server 跳过、mainClass 映射」的规则，能不能真的产出 SHA1 对得上的 patched client jar。
Python 版与 Kotlin 版是独立实现，两边算出来的参数一致才算这套规则可信。

跑法：python verify_forge.py
目标：把 1.20.1-47.2.0 的 6 个 client processor 全部跑通，产出 patched client jar。
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor

_DEFAULT_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "forge-verify")
ROOT = os.environ.get("FORGE_VERIFY_ROOT", os.path.abspath(_DEFAULT_ROOT))
_JAVA_HOME = os.environ.get("JAVA_HOME", r"X:\android\.toolchain\jdk\jdk-17.0.21+9")
JDK = os.path.join(_JAVA_HOME, "bin", "java.exe")
FORGE_MAVEN = "https://maven.minecraftforge.net/"
CENTRAL = "https://repo1.maven.org/maven2/"
UA = {"User-Agent": "mineport-dev/0.1"}
LIBS = os.path.join(ROOT, "libraries")


def maven_path(coord: str) -> str:
    at = coord.find("@")
    ext = coord[at + 1:] if at >= 0 else "jar"
    body = coord[:at] if at >= 0 else coord
    p = body.split(":")
    cls = "-" + p[3] if len(p) > 3 else ""
    return f"{p[0].replace('.', '/')}/{p[1]}/{p[2]}/{p[1]}-{p[2]}{cls}.{ext}"


def fetch(coord: str, url: str | None = None) -> str:
    rel = maven_path(coord)
    target = os.path.join(LIBS, rel)
    if os.path.isfile(target) and os.path.getsize(target) > 0:
        return target
    os.makedirs(os.path.dirname(target), exist_ok=True)
    urls = ([url] if url else []) + [FORGE_MAVEN + rel, CENTRAL + rel]
    for u in urls:
        try:
            req = urllib.request.Request(u, headers=UA)
            with urllib.request.urlopen(req, timeout=180) as r, open(target, "wb") as f:
                f.write(r.read())
            return target
        except Exception:
            continue
    raise RuntimeError(f"下载失败 {coord}")


def sha1_of(path: str) -> str:
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    installer = os.path.join(ROOT, "forge-installer.jar")
    if not os.path.isfile(installer):
        os.makedirs(ROOT, exist_ok=True)
        url = FORGE_MAVEN + "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-installer.jar"
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=180) as r, open(installer, "wb") as f:
            f.write(r.read())
    print("installer", os.path.getsize(installer))

    with zipfile.ZipFile(installer) as z:
        profile = json.loads(z.read("install_profile.json").decode("utf-8"))
        version = json.loads(z.read("version.json").decode("utf-8"))
        data_dir = os.path.join(ROOT, "data")
        os.makedirs(data_dir, exist_ok=True)
        with z.open("data/client.lzma") as src, open(os.path.join(data_dir, "client.lzma"), "wb") as dst:
            dst.write(src.read())

    # 1) 原版 client jar
    mc_jar = os.path.join(ROOT, "versions", "1.20.1", "1.20.1.jar")
    if not os.path.isfile(mc_jar):
        req = urllib.request.Request("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", headers=UA)
        manifest = json.loads(urllib.request.urlopen(req, timeout=120).read())
        entry = next(v for v in manifest["versions"] if v["id"] == "1.20.1")
        vjson = json.loads(urllib.request.urlopen(urllib.request.Request(entry["url"], headers=UA), timeout=120).read())
        os.makedirs(os.path.dirname(mc_jar), exist_ok=True)
        with urllib.request.urlopen(urllib.request.Request(vjson["downloads"]["client"]["url"], headers=UA), timeout=600) as r, open(mc_jar, "wb") as f:
            f.write(r.read())
    print("client jar", os.path.getsize(mc_jar))

    # 2) 库清单
    wanted: dict[str, str | None] = {}
    for src in (version, profile):
        for lib in src.get("libraries", []):
            name = lib.get("name")
            if not name:
                continue
            url = ((lib.get("downloads") or {}).get("artifact") or {}).get("url")
            wanted[name] = url
    # processor args 里用到的 [coord] 也要能下载（mcp_config zip 等）
    for proc in profile["processors"]:
        for arg in proc.get("args", []):
            if arg.startswith("[") and arg.endswith("]"):
                wanted.setdefault(arg[1:-1], None)
        for cp in proc.get("classpath", []):
            wanted.setdefault(cp, None)
        wanted.setdefault(proc["jar"], None)

    print("libraries", len(wanted))
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda kv: fetch(kv[0], kv[1]), wanted.items()))

    # 3) 变量结算
    def var_path(coord: str) -> str:
        """data 里的坐标是 processor 的**输出**位置：只算路径，不下载。"""
        target = os.path.join(LIBS, maven_path(coord))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        return target

    def resolve_var(raw: str) -> str:
        if raw.startswith("[") and raw.endswith("]"):
            return var_path(raw[1:-1])
        if raw.startswith("/"):
            return os.path.join(data_dir, os.path.basename(raw))
        if raw.startswith("'") and raw.endswith("'"):
            return raw[1:-1]
        return raw

    variables = {k: resolve_var(v.get("client", "")) for k, v in profile["data"].items()}
    variables.update({
        "ROOT": ROOT,
        "MINECRAFT_JAR": mc_jar,
        "INSTALLER": installer,
        "SIDE": "client",
    })

    mains = {
        "net.minecraftforge:installertools": "net.minecraftforge.installertools.ConsoleTool",
        "net.minecraftforge:jarsplitter": "net.minecraftforge.jarsplitter.ConsoleTool",
        "net.minecraftforge:ForgeAutoRenamingTool": "net.minecraftforge.fart.Main",
        "net.minecraftforge:binarypatcher": "net.minecraftforge.binarypatcher.ConsoleTool",
    }

    def substitute(arg: str) -> str:
        if arg.startswith("[") and arg.endswith("]"):
            return fetch(arg[1:-1])
        for key, val in variables.items():
            arg = arg.replace("{" + key + "}", val)
        return arg

    # 4) 逐个跑
    for i, proc in enumerate(profile["processors"]):
        sides = proc.get("sides") or []
        if sides and "client" not in sides:
            print(f"[{i}] skip (server)")
            continue
        jar = proc["jar"]
        main = next((m for k, m in mains.items() if jar.startswith(k)), None)
        if not main:
            print(f"[{i}] skip (no main for {jar})")
            continue
        cp = [fetch(jar)] + [fetch(c) for c in proc.get("classpath", [])]
        args = [substitute(a) for a in proc.get("args", [])]
        cmd = [JDK, "-Xmx2G", "-cp", os.pathsep.join(cp), main] + args
        print(f"[{i}] {main}")
        proc_obj = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
        if proc_obj.returncode != 0:
            print(proc_obj.stdout[-3000:])
            print(proc_obj.stderr[-3000:])
            return proc_obj.returncode

    # 5) 校验
    patched = variables.get("PATCHED")
    expect = profile["data"]["PATCHED_SHA"]["client"].strip("'")
    got = sha1_of(patched)
    print("PATCHED", patched, os.path.getsize(patched))
    print("sha1", got, "expect", expect, "OK" if got == expect else "MISMATCH")
    return 0 if got == expect else 2


if __name__ == "__main__":
    sys.exit(main())
