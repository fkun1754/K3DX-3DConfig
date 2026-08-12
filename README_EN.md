# K3DX-3DConfig

> **Unified 3D configuration tool for naked-eye 3D phones** (KDX / Kangde Xin technology)

**Language: English**（[中文](./README.md)）

---

## 📌 About

Naked-eye 3D phones based on KDX (Kangde Xin) technology have fragmented 3D features across multiple system components:

- **3D games**: rely on the system libGLES injection layer reading `.gles.cfg`
- **3D apps**: rely on the system 3DService SBS-to-3D conversion (floating button), incomplete functionality with whitelist restrictions

**Why this project exists**: theoretically works on **all naked-eye 3D devices using KDX technology** — to **unify the 3D experience across KDX 3D phones** with a single app covering game 3D config, app SBS-to-3D.

> ⚠️ **AI-built project**: This project was **entirely developed by AI** using **DeepSeek V4 Flash + Hermes Agent** (source code, reverse engineering, and debugging iterations).

---

## 📱 Device Compatibility

| Device | 3D Game Config | Game Depth Adjust | 3D App (SBS→3D) | Notes |
|------|:---:|:---:|:---:|------|
| **K3DX-V5G** (ZTE/nubia) | ✅ | ✅ | ✅ | **Fully working** (dev/test device) |
| **ZTE C2017** | ✅ | ❌ | ✅ | No game depth adjust, rest works |
| **koobee F2** | ✅ | ✅ | ❌ | Game depth works; 3D app not usable |
| **Changhong X1** | ❌ | ❌ | ❌ | Not working |
| **Unno P8** | ❓ | ❓ | ❓ | Untested |
| **D Color 7.0** | ❓ | ❓ | ❓ | Untested |
| **Blackview P2 Lite 3D** | ❓ | ❓ | ❓ | Untested |
| **Elephone P8 3D** | ❓ | ❓ | ❓ | Untested |
| **Konka Phantom T1** | ❓ | ❓ | ❓ | Untested |
| **Tianjin BlueCool LK7** | ❓ | ❓ | ❓ | Untested |

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

**Storage**

| Permission | Purpose | Condition |
|------|------|------|
| `WRITE/READ_EXTERNAL_STORAGE` | Read/write `/storage/emulated/0/.gles.cfg` | Android 6.0+ runtime |
| `MANAGE_EXTERNAL_STORAGE` | Access storage root under scoped storage | Android 11+ settings grant |

**UI & Notifications**

| Permission | Purpose | Condition |
|------|------|------|
| `SYSTEM_ALERT_WINDOW` | Floating window | Android 6.0+ settings grant |
| `POST_NOTIFICATIONS` | Notification quick toggle | Android 13+ runtime |

**Background**

| Permission | Purpose | Condition |
|------|------|------|
| `FOREGROUND_SERVICE` | Foreground service | Android 9+ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Background persistence | Android 6.0+ |

> **⚠️ About game depth adjust**: writing `persist.sys.3deffect` (depth) requires **SELinux permissive mode**.
> - When SELinux is Enforcing, enabling the game depth float bar prompts for root (**once only**)
> - With root, the app automatically runs `setenforce 0` — no manual steps needed
> - Devices without root on Enforcing SELinux cannot adjust depth (Permissive devices like V5G unaffected)
| `RECEIVE_BOOT_COMPLETED` | Boot autostart | Normal |

**App Detection**

| Permission | Purpose | Condition |
|------|------|------|
| `PACKAGE_USAGE_STATS` | Foreground app detection | All versions, settings grant (AppOps) |

## 🧠 Implementation (Summary)

### 1. 3D Games — `.gles.cfg` mechanism
KDX libGLES injection layer (`libGLES_kdx.so`) reads `/storage/emulated/0/.gles.cfg` at game startup:

```
package_name  32-byte config token (Base64)
```

- 32-byte layout: `[0:3]='KDX'` `[3]=0x1c` `[4:8]=checksum` `[8]=enableMix` `[9]=0xff` `[10]=eye` `[11]=parallax_adj` `[12]=focus_plane` `[13]=near_plane` `[14]=0x64` `[15:31]=0`
- **`[4:8]` is a vendor-proprietary checksum of the parameter area** (verified by reverse engineering: 15 same-parameter configs share identical IDs; no standard hash matches) — tokens can only be reused from official configs
- This project embeds **119 official tokens** (covering all official parameter combinations); rewriting with another package name works
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
- **process whitelist**: root writes `process_white.db` of `cn.nubia.processmanager`

---

## 🔧 Build

Pure command-line build (no Gradle): `aapt2 → javac → d8 → zipalign → apksigner`

```bash
python build.py
```

**Dependencies**: Android SDK (build-tools 35.0.0 + platform android-31), JDK, Python 3

**Android support**: minSdk 19 (Android 4.4) to latest

---

---

## 📷 Screenshots

<table align="center"><tr>
<td align="center"><img src="screenshots/01-two-config-modes.png" width="130"/><br/><sub>1. Two config modes</sub></td>
<td align="center"><img src="screenshots/02-game-3d-config.png" width="130"/><br/><sub>2. 3D game config</sub></td>
<td align="center"><img src="screenshots/03-app-3d-config.png" width="130"/><br/><sub>3. 3D app config</sub></td>
<td align="center"><img src="screenshots/04-depth-adjust.png" width="130"/><br/><sub>4. In-game depth adjust</sub></td>
<td align="center"><img src="screenshots/05-hsbs.png" width="130"/><br/><sub>5. HSBS (half SBS)</sub></td>
<td align="center"><img src="screenshots/06-fsbs.png" width="130"/><br/><sub>6. FSBS (no fullscreen)</sub></td>
<td align="center"><img src="screenshots/07-2d-to-3d.png" width="130"/><br/><sub>7. 2D-to-3D (layering)</sub></td>
</tr></table>

- **1**: Choose type per app — 3D Game (.gles.cfg) or 3D App (SBS to 3D); checking saves defaults
- **2**: Official template groups + depth adjust (0-20) + floating bar toggle & "enable all"
- **3**: Viewpoint switch (VP1/VP2) + auto-3D on launch (HSBS/FSBS/2D3D) + floating button
- **4**: Translucent slider adjusts depth in-game; edge-collapse, tap/swipe to expand
- **5**: HSBS half SBS — half-width frames combined into stereo
- **6**: FSBS full SBS, no compression; cannot go fullscreen (system limitation)
- **7**: 2D-to-3D via simple algorithm layering (not AI)

---

## 📝 Changelog

- **1.2**: Search on both app picker and template picker (recommended templates on top); independent mode/viewpoint button toggles on 3D-app floating window; fixed V5G viewpoint not following 180° landscape rotation (auto-switch added); UI & performance polish
- **1.1**: Viewpoint switch button (right-left/left-right) on 3D-app floating window; fixed process guard & config persistence bugs; UI polish
- **1.0**: First open-source release: complete 3D game/app config + floating windows

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
