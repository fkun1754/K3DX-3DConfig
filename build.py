#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K3DX 3D游戏配置器 编译脚本 (Windows, 无 Gradle)
链: aapt2 compile -> aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner
"""
import os, subprocess, shutil, glob, sys, zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
SDK = os.environ.get("LOCALAPPDATA", r"C:\Users\fkun1\AppData\Local") + r"\Android\Sdk"
BT = os.path.join(SDK, "build-tools", "35.0.0")
PLATFORM = os.path.join(SDK, "platforms", "android-31")
ANDROID_JAR = os.path.join(PLATFORM, "android.jar")
if not os.path.exists(ANDROID_JAR):
    for v in ["android-35", "android-34", "android-19"]:
        p = os.path.join(SDK, "platforms", v, "android.jar")
        if os.path.exists(p):
            ANDROID_JAR = p
            break
print("ANDROID_JAR:", ANDROID_JAR)

AAPT2 = os.path.join(BT, "aapt2.exe")
D8 = os.path.join(BT, "lib", "d8.jar")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "lib", "apksigner.jar")
KEYSTORE = os.environ.get("KEYSTORE", "mod.keystore")   # 自己的签名 keystore（可环境变量指定）
KEYALIAS = os.environ.get("KEYALIAS", "mod")
KEYPASS = os.environ.get("KEYPASS", "android")

def run(cmd, **kw):
    print(">>", " ".join(cmd) if isinstance(cmd, list) else cmd)
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.stdout: print(r.stdout[-3000:])
    if r.stderr: print("STDERR:", r.stderr[-3000:])
    if r.returncode != 0:
        raise SystemExit(f"命令失败: {cmd}")
    return r

os.chdir(BASE)
for d in ["build", "build/gen", "build/classes", "build/dex", "output"]:
    os.makedirs(d, exist_ok=True)

# 1. aapt2 compile 资源 (--dir 保留路径结构)
res_zip = os.path.join("build", "res.zip")
if os.path.exists(res_zip): os.remove(res_zip)
run([AAPT2, "compile", "--dir", "res", "-o", res_zip])

# 2. aapt2 link (含 assets)
manifest = os.path.join(BASE, "AndroidManifest.xml")
assets_dir = os.path.join(BASE, "assets")
link_cmd = [AAPT2, "link", "-o", os.path.join("build", "base.apk"),
     "-I", ANDROID_JAR, "--manifest", manifest,
     "--java", os.path.join("build", "gen"),
     "--min-sdk-version", "19", "--target-sdk-version", "22",
     "--version-code", "2", "--version-name", "2.0"]
if os.path.isdir(assets_dir):
    link_cmd += ["-A", assets_dir]
run(link_cmd + [res_zip])

# 3. javac
srcs = []
for root, _, files in os.walk("src"):
    for fn in files:
        if fn.endswith(".java"):
            srcs.append(os.path.join(root, fn))
gen_dir = os.path.join(BASE, "build", "gen")
cp = ANDROID_JAR + ";" + gen_dir
run(["javac", "-encoding", "UTF-8", "-source", "1.7", "-target", "1.7",
     "-cp", cp, "-d", os.path.join("build", "classes")] + srcs)

# 4. d8 (直接调 jar，避免 .bat 在 git-bash 静默失败)
classes = glob.glob(os.path.join("build", "classes", "**", "*.class"), recursive=True)
run(["java", "-cp", D8, "com.android.tools.r8.D8",
     "--lib", ANDROID_JAR, "--release", "--min-api", "19",
     "--output", os.path.join("build", "dex")] + classes)

# 5. 打包: base.apk + classes.dex
base_apk = os.path.join("build", "base.apk")
out_unsigned = os.path.join("build", "unsigned.apk")
shutil.copy(base_apk, out_unsigned)
with zipfile.ZipFile(out_unsigned, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join("build", "dex", "classes.dex"), "classes.dex")

# 6. zipalign
aligned = os.path.join("build", "aligned.apk")
run([ZIPALIGN, "-f", "4", out_unsigned, aligned])

# 7. 签名
signed = os.path.join("output", "KDX3DGameConfig-v2.0.apk")
run(["java", "-jar", APKSIGNER, "sign", "--ks", KEYSTORE,
     "--ks-pass", f"pass:{KEYPASS}", "--ks-key-alias", KEYALIAS,
     "--out", signed, aligned])
print("\n✅ 完成:", signed)
