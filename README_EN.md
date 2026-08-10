# K3DX-3DConfig

> **Unified 3D configuration tool for naked-eye 3D phones** (KDX / Kangde Xin technology)

**Version: 1.0** ｜ **Language: English**（[中文](./README.md)）

---

## 📌 About

Naked-eye 3D phones based on KDX (Kangde Xin) technology have fragmented 3D features across multiple system components:

- **3D games**: rely on the system libGLES injection layer reading `.gles.cfg`
- **3D apps**: rely on the system 3DService SBS-to-3D conversion (floating button), hidden entry with limited functionality

**Why this project exists**: theoretically works on **all naked-eye 3D devices using KDX technology** — to **unify the 3D experience across KDX 3D phones** with a single app covering game 3D config, app SBS-to-3D, depth adjustment and floating windows.

> ⚠️ **AI-built project**: This project was **entirely developed by AI** using **DeepSeek V4 Flash + Hermes Agent** (source code, reverse engineering, and debugging iterations).

---

## 📱 Device Compatibility

| Device | 3D Game Config | Game Depth Adjust | 3D App (SBS→3D) | Notes |
|------|:---:|:---:|:---:|------|
| **K3DX-V5G** (ZTE/nubia) | ✅ | ✅ | ✅ | **Fully working** (dev/test device) |
| **ZTE C2017** | ✅ | ❌ | ✅ | No game depth adjustment; everything else works |
| **koobee F2** | ✅ | ✅ | ❓ | Game depth works; 3D app not tested |
| **Changhong X1** | ❌ | ❌ | ❌ | Not working |

---

## ✨ Features

### 🎮 3D Games
- `.gles.cfg` management: **119 official config tokens** built-in (grouped by depth-scene / flat-layered), one-tap write & quick match
- Auto-creates config file if missing
- **Per-game 3D depth floating bar** (translucent slider, real-time adjust) + global "enable all" option
- Per-app depth memory + template selection memory (persisted)

### 📺 3D Apps (SBS → 3D)
- **3 display modes**: HSBS (half SBS) / FSBS (full SBS) / 2D-to-3D
- **Viewpoint switch**: VP1 (right-left) / VP2 (left-right)
- Auto-enable 3D on app launch (mode selectable)
- Floating buttons: dual stacked buttons (main 2D↔3D toggle + mode cycler), instant response; auto-collapse to edge strip, tap/swipe to expand

### 🔒 Process Guard (optional, root)
- Magisk module: boot autostart + auto-restart if killed + OOM protection
- system process whitelist injection (background persistence)

---

## 🔐 Permissions

| Permission | Purpose | Required On |
|------|------|-----------|
| `WRITE/READ_EXTERNAL_STORAGE` | Read/write `/storage/emulated/0/.gles.cfg` | 6.0+ runtime |
| `SYSTEM_ALERT_WINDOW` | Floating window | 6.0+ settings |
| `PACKAGE_USAGE_STATS` | Foreground app detection | Settings grant |
| `RECEIVE_BOOT_COMPLETED` | Boot autostart | Normal |
| `FOREGROUND_SERVICE` | Foreground service | Android 9+ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Background persistence | Android 6+ |
| `POST_NOTIFICATIONS` | Notification quick toggle | Android 13+ |
| `MANAGE_EXTERNAL_STORAGE` | Write root-dir config under scoped storage | Android 11+ |

---

## 🧠 Implementation (Summary)

### 1. 3D Games — `.gles.cfg` mechanism
KDX libGLES injection layer (`libGLES_kdx.so`) reads `/sdcard/.gles.cfg` at game startup:

```
package_name  32-byte config token (Base64)
```

- 32-byte layout: `[0:3]='KDX'` `[3]=0x1c` `[4:8]=checksum` `[8]=enableMix` `[9]=0xff` `[10]=eye` `[11]=parallax_adj` `[12]=focus_plane` `[13]=near_plane` `[14]=0x64` `[15:31]=0`
- **`[4:8]` is a vendor-proprietary checksum of the parameter area** (verified by reverse engineering: 15 same-parameter configs share identical IDs; no standard hash matches) — tokens can only be reused from official configs
- This project embeds **119 official tokens** (covering all official parameter combinations); rewriting with another package name works 100%
- Depth: `setprop persist.sys.3deffect (0-20)`

### 2. 3D Apps — SBS-to-3D interface
System 3DService (`com.wztech.service3d`) talks to a **custom SurfaceFlinger extension via binder** (`MyService.send()`). Its JNI lib `libnative_wz2sf.so` exports `Java_com_wztech_service3d_Service3D_send2sf` — this project binds to it **zero-compile via same-name class + System.load**:

```
send2sf(8001, (msg << 16) | value)
```

| msg | Meaning | value |
|-----|---------|-------|
| 100 | 3D master switch | 1=on 0=off |
| 101 | LR swap / viewpoint | VP01=1 VP02=0 |
| 103 | Display mode | **0=HSBS 1=FSBS 2=2D-to-3D** |

### 3. Foreground app detection
On Android 5.1 `getRunningTasks`/`getRunningAppProcesses` are restricted for third-party apps; this project uses **UsageStatsManager** (`queryEvents`, last `MOVE_TO_FOREGROUND`) instead.

### 4. Process guard (optional root)
- **Magisk module** (`assets/kdx3d_guard.zip`): `service.sh` runs a persistent loop at boot — checks the toggle, auto-restarts the service if killed, sets `oom_score_adj=-17`
- **nubia whitelist**: root writes `process_white.db` of `cn.nubia.processmanager`

---

## 🔧 Build

Pure command-line build (no Gradle): `aapt2 → javac → d8 → zipalign → apksigner`

```bash
python build.py
```

**Dependencies**: Android SDK (build-tools 35.0.0 + platform android-31), JDK, Python 3

**Android support**: minSdk 19 (Android 4.4) to latest

---

## 📦 Version History

- **v1.0** (current): first open-source release

---

## 💬 Contact

QQ Tech Group: **869206374**

---

## ⚠️ Disclaimer

- For **technical learning and personal use only**; involves system interfaces (binder, properties, JNI loading of system libs)
- Use root features at your own risk
- Not affiliated with KDX / ZTE / nubia or any vendor

## 📄 License

[MIT](./LICENSE)
