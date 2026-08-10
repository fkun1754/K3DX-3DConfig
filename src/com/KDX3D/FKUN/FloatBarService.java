package com.KDX3D.FKUN;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/**
 * 双模式悬浮窗服务：
 *  - 3D游戏（.gles.cfg 配置 + 该应用 3D深度悬浮窗开关开）→ 半透明深度滑条
 *  - 3D应用（app3d_<pkg> 配置 + 悬浮按钮开）→ 半透明白色圆形按钮
 *     单击=HSBS转3D、双击=FSBS、三击=2D转3D、长按=回2D
 *  - 前台应用不在配置列表 → 无悬浮窗
 * 隐藏策略：靠边1秒收缩成边缘阴影，滑动唤出
 */
public class FloatBarService extends Service {

    private static final String TAG = "FloatBar";
    private static final String PREFS = "floatbar";
    private static final String KEY_X = "bar_x";
    private static final String KEY_Y = "bar_y";
    private static final String KEY_EDGE = "bar_edge";
    private static final String ACTION_TOGGLE = "com.KDX3D.FKUN.TOGGLE_BAR";
    private static final int NOTIFY_ID = 1;

    private WindowManager wm;
    private SharedPreferences prefs;
    private Handler handler = new Handler();
    private Set<String> cfgPkgs = new HashSet<String>();
    private String lastTopPkg = null;

    // 视图：0=无, 1=游戏深度条, 2=3D应用按钮
    private int currentType = 0;
    private View barView;          // 游戏深度条
    private View btnView;          // 3D应用圆形按钮
    private View curView = null;
    private WindowManager.LayoutParams params;
    private boolean shown = false;
    private boolean expanded = true;

    private SeekBar sbDepth;
    private View barContent, barEdgeHint;
    private android.widget.FrameLayout barContainer;
    private TextView tvBtnText;
    private TextView tvModeText;
    private TextView tvVpText;
    private View btnEdgeHint;
    private int currentVp = 1;   // 1=VP01(右左) 2=VP02(左右)
    private int currentMode = 0;   // 0=HSBS 1=FSBS 2=2D3D
    private static final String[] MODES = {"HSBS", "FSBS", "2D3D"};

    private int screenW, screenH;
    private int barW, barH;       // 展开尺寸
    private int edgeW, edgeH;     // 收缩触摸区
    private int edgeSide = -1;
    private float downX, downY;
    private int startX, startY;
    private boolean dragging = false;
    private boolean slidingOut = false;

    // 圆形按钮点击计数（单击/双击/三击）
    private boolean is3D = false;   // 当前是否 3D 模式（按钮文字 3D）
    private String autoAppliedPkg = null;  // 已应用 auto 3D 的应用（避免重复覆盖）

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 防重复：Activity 旋转重建会重复 startService，先取消旧 monitor 避免并发实例
        handler.removeCallbacks(monitor);
        updateNotification();
        handler.postDelayed(monitor, 500);
        return START_STICKY;
    }

    /** 通知栏常驻通知 + 显示/隐藏快捷按钮 */
    private void updateNotification() {
        try {
            // Android 8+ (API 26+) 需要通知渠道
            android.app.Notification.Builder nb;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "kdx3d", "KDX3D悬浮窗", android.app.NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
                nb = new android.app.Notification.Builder(this, "kdx3d");
            } else {
                nb = new android.app.Notification.Builder(this);
            }
            Intent toggle = new Intent(ACTION_TOGGLE);
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            nb.setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("KDX-3D配置")
                    .setContentText(shown ? "3D悬浮窗运行中" : "3D悬浮窗已隐藏")
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class), 0))
                    .addAction(android.R.drawable.ic_menu_view,
                            shown ? "隐藏悬浮窗" : "显示悬浮窗", pi);
            startForeground(NOTIFY_ID, nb.build());
        } catch (Exception e) { }
    }

    /** 控制中心按钮：切换悬浮窗显示/隐藏 */
    private android.content.BroadcastReceiver barToggleReceiver =
            new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_TOGGLE.equals(intent.getAction())) {
                if (shown) removeBar(); else showCurrent();
                updateNotification();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
        registerReceiver(barToggleReceiver, filter);
        updateScreenSize();
        barW = dp(230);
        barH = dp(70);
        edgeW = dp(25);
        edgeH = dp(60);

        // 游戏深度条视图
        barView = LayoutInflater.from(this).inflate(R.layout.float_bar, null);
        barContainer = (android.widget.FrameLayout) barView.findViewById(R.id.bar_container);
        barContent = barView.findViewById(R.id.bar_content);
        barEdgeHint = barView.findViewById(R.id.bar_edge_hint);
        sbDepth = (SeekBar) barView.findViewById(R.id.sb_bar_depth);
        int depth = 10;
        try {
            String v = getProp("persist.sys.3deffect");
            if (v != null && v.trim().length() > 0) depth = Integer.parseInt(v.trim());
        } catch (Exception e) { }
        sbDepth.setProgress(depth);
        sbDepth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    setDepth(p);
                    if (lastTopPkg != null) {
                        prefs.edit().putInt("depth_" + lastTopPkg, p).apply();
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                handler.removeCallbacks(hideRunnable);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (expanded) scheduleHide(3000);
            }
        });
        barView.setOnTouchListener(barTouchListener);

        // 3D应用圆形按钮视图（双按钮：主按钮2D/3D + 模式按钮）
        btnView = LayoutInflater.from(this).inflate(R.layout.app3d_btn, null);
        tvBtnText = (TextView) btnView.findViewById(R.id.tv_btn_text);
        tvModeText = (TextView) btnView.findViewById(R.id.tv_mode_text);
        tvVpText = (TextView) btnView.findViewById(R.id.tv_vp_text);
        btnEdgeHint = btnView.findViewById(R.id.btn_edge_hint);
        btnView.setOnTouchListener(btnTouchListener);

        params = new WindowManager.LayoutParams(
                barW, barH,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        int sx = prefs.getInt(KEY_X, screenW - barW - dp(10));
        int sy = prefs.getInt(KEY_Y, screenH / 3);
        edgeSide = prefs.getInt(KEY_EDGE, -1);
        params.x = sx;
        params.y = sy;
        if (edgeSide >= 0) {
            expanded = false;
            applyEdgeSize();
        }

        handler.postDelayed(sizeMonitor, 1000);
    }

    /** 前台监控：分类显示对应悬浮窗 */
    private Runnable monitor = new Runnable() {
        @Override
        public void run() {
            try {
                String top = getTopPkg();
                if (top == null) {
                    // 检测失败（权限丢失/异常）：保持当前显示状态，不因检测失败关闭悬浮窗
                    handler.postDelayed(this, 500);
                    return;
                }
                boolean changed = !top.equals(lastTopPkg);
                if (changed || cfgPkgs.isEmpty()) {
                    lastTopPkg = top;
                    loadCfgPkgs();
                }
                // 无全局开关：服务常驻，仅按各应用配置判断显示
                boolean fbAll = prefs.getBoolean("fb_all", false);   // 全部游戏开启深度悬浮窗
                int targetType = 0;
                if (top != null) {
                    if (cfgPkgs.contains(top)
                            && (fbAll || prefs.getBoolean("fb_" + top, false))) {
                        targetType = 1;   // 游戏深度条
                    } else if (prefs.getString("app3d_" + top, null) != null) {
                        // 3D应用：解析配置
                        try {
                            org.json.JSONObject o = new org.json.JSONObject(
                                    prefs.getString("app3d_" + top, "{}"));
                            if (o.optInt("btn", 1) == 1) {
                                targetType = 2; // 圆形按钮
                            }
                            // 启动时直接开启3D（自动模式）：仅在应用切换时应用一次，
                            // 避免每 tick 重复设置覆盖用户手动切换
                            if (!top.equals(autoAppliedPkg)) {
                                autoAppliedPkg = top;
                                if (o.optInt("auto", 0) == 1) {
                                    com.wztech.service3d.Service3D.set3DEnabled(true);
                                    com.wztech.service3d.Service3D.setDisplayMode(o.optInt("mode", 0));
                                    tvBtnText.setText("3D");
                                    is3D = true;
                                } else {
                                    tvBtnText.setText("2D");
                                    is3D = false;
                                }
                            }
                        } catch (Exception e) { }
                    }
                }
                if (targetType != currentType) {
                    // 类型变化：离开3D应用 → 取消3D模式（只在该应用前台时保持3D）
                    if (currentType == 2) {
                        com.wztech.service3d.Service3D.set3DEnabled(false);
                        tvBtnText.setText("2D");
                        is3D = false;
                        autoAppliedPkg = null;
                    }
                    // 切换视图
                    if (shown) removeBar();
                    currentType = targetType;
                    if (targetType == 2) {
                        // 按钮初始状态：2D（等点击切换）
                        if (!com.wztech.service3d.Service3D.load()) {
                            tvBtnText.setText("2D");
                        }
                    }
                }
                if (targetType != 0 && !shown) showCurrent();
                if (targetType == 0 && shown) removeBar();
            } catch (Exception e) { }
            handler.postDelayed(this, 500);
        }
    };

    private void showCurrent() {
        if (currentType == 1) {
            curView = barView;
            params.width = expanded ? barW : edgeW;
            params.height = expanded ? barH : edgeH;
            // 应用该游戏记忆深度
            if (lastTopPkg != null) {
                int d = prefs.getInt("depth_" + lastTopPkg, -1);
                if (d < 0) d = sbDepth.getProgress();
                sbDepth.setProgress(d);
                setDepth(d);
            }
        } else if (currentType == 2) {
            curView = btnView;
            params.width = expanded ? dp(68) : edgeW;
            params.height = expanded ? dp(124) : edgeH;
            // 初始化展开视觉（外框 btn_frame）
            if (expanded) {
                btnView.setBackgroundColor(0x00000000);
                btnView.findViewById(R.id.btn_container)
                        .setBackgroundResource(R.drawable.btn_frame);
                btnView.findViewById(R.id.btn_main).setVisibility(View.VISIBLE);
                btnView.findViewById(R.id.btn_mode).setVisibility(View.VISIBLE);
                btnEdgeHint.setVisibility(View.GONE);
                tvBtnText.setVisibility(View.VISIBLE);
                tvBtnText.setTextSize(16);
                tvModeText.setVisibility(View.VISIBLE);
            }
        } else return;
        try {
            wm.addView(curView, params);
            shown = true;
        } catch (Exception e) { }
    }

    private void removeBar() {
        try {
            if (curView != null) wm.removeView(curView);
        } catch (Exception e) { }
        shown = false;
        curView = null;
    }

    /** 屏幕尺寸刷新 */
    private Runnable sizeMonitor = new Runnable() {
        @Override
        public void run() {
            updateScreenSize();
            handler.postDelayed(this, 1000);
        }
    };

    private void updateScreenSize() {
        screenW = wm.getDefaultDisplay().getWidth();
        screenH = wm.getDefaultDisplay().getHeight();
    }

    /** 收缩状态尺寸（贴边） */
    private void applyEdgeSize() {
        params.width = edgeW;
        params.height = edgeH;
        if (edgeSide == 0) params.x = 0;
        else if (edgeSide == 1) params.x = screenW - edgeW;
    }

    private String getTopPkg() {
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    getSystemService("usagestats");
            long end = System.currentTimeMillis();
            // 永久窗口（从时间起点查）：避免长时间停留导致事件过期查不到前台
            android.app.usage.UsageEvents events = usm.queryEvents(0L, end);
            if (events != null) {
                android.app.usage.UsageEvents.Event e =
                        new android.app.usage.UsageEvents.Event();
                String lastPkg = null;
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastPkg = e.getPackageName();
                    }
                }
                if (lastPkg != null && !"com.KDX3D.FKUN".equals(lastPkg)) {
                    return lastPkg;
                }
            }
        } catch (Exception e) { }
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<ActivityManager.RunningAppProcessInfo> procs =
                    am.getRunningAppProcesses();
            if (procs != null && !procs.isEmpty()) {
                ActivityManager.RunningAppProcessInfo top = procs.get(0);
                if (top != null && top.processName != null
                        && top.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND
                        && !"com.KDX3D.FKUN".equals(top.processName)) {
                    String pkg = top.processName;
                    int idx = pkg.indexOf(':');
                    if (idx > 0) pkg = pkg.substring(0, idx);
                    return pkg;
                }
            }
        } catch (Exception e) { }
        return null;
    }

    private void loadCfgPkgs() {
        cfgPkgs.clear();
        try {
            File f = new File("/storage/emulated/0/.gles.cfg");
            if (!f.exists()) f = new File("/storage/emulated/0/.gles.cfg");
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 1) cfgPkgs.add(parts[0]);
            }
            br.close();
        } catch (Exception e) { }
    }

    // ============ 游戏深度条触摸（拖动+收缩+唤出） ============
    private View.OnTouchListener barTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return handleBarTouch(event);
        }
    };

    // ============ 3D应用双按钮（官方风格：点击立即响应，无延迟判定） ============
    /** 主按钮动作：2D ↔ 3D 切换（3D 用当前模式） */
    private void toggleMain() {
        if (is3D) {
            com.wztech.service3d.Service3D.set3DEnabled(false);
            tvBtnText.setText("2D");
            is3D = false;
        } else {
            com.wztech.service3d.Service3D.set3DEnabled(true);
            com.wztech.service3d.Service3D.setDisplayMode(currentMode);
            tvBtnText.setText("3D");
            is3D = true;
            applyAppViewPoint();
        }
        scheduleHide(3000);
    }

    /** 模式按钮动作：HSBS → FSBS → 2D3D 循环（3D 中立即生效） */
    private void cycleMode() {
        currentMode = (currentMode + 1) % 3;
        tvModeText.setText(MODES[currentMode]);
        if (is3D) {
            com.wztech.service3d.Service3D.setDisplayMode(currentMode);
            applyAppViewPoint();
        }
        scheduleHide(3000);
    }

    /** 视角按钮动作：视角1 ↔ 视角2 切换（3D 中立即生效） */
    private void cycleViewpoint() {
        currentVp = (currentVp == 1) ? 2 : 1;
        tvVpText.setText("视角" + currentVp);
        com.wztech.service3d.Service3D.setViewPoint(currentVp == 1 ? 1 : 0);
        // 同步写入 .3d.properties
        if (lastTopPkg != null) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(
                        prefs.getString("app3d_" + lastTopPkg, "{}"));
                o.put("viewpoint", currentVp);
                prefs.edit().putString("app3d_" + lastTopPkg, o.toString()).apply();
            } catch (Exception e) { }
        }
        scheduleHide(3000);
    }

    /** 应用当前应用配置的视角 */
    private void applyAppViewPoint() {
        if (lastTopPkg == null) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject(
                    prefs.getString("app3d_" + lastTopPkg, "{}"));
            int vp = o.optInt("viewpoint", 1);
            com.wztech.service3d.Service3D.setViewPoint(vp == 1 ? 1 : 0);
        } catch (Exception e) { }
    }

    private View.OnTouchListener btnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    slidingOut = false;
                    handler.removeCallbacks(hideRunnable);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!expanded) {
                        if (Math.abs(dx) > 40 && !slidingOut) slidingOut = true;
                        return true;
                    }
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragging = true;
                        params.x = startX + (int) dx;
                        params.y = startY + (int) dy;
                        if (params.x < 0) params.x = 0;
                        if (params.x > screenW - dp(68)) params.x = screenW - dp(68);
                        if (params.y < 0) params.y = 0;
                        if (params.y > screenH - dp(124)) params.y = screenH - dp(124);
                        wm.updateViewLayout(btnView, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!expanded) {
                        // 收缩态：滑动或点击都唤出
                        expandBar();
                        slidingOut = false;
                        return true;
                    }
                    if (dragging) {
                        // 靠边判定
                        if (params.x < dp(60)) {
                            edgeSide = 0;
                            prefs.edit().putInt(KEY_EDGE, 0).apply();
                            scheduleHide(1000);
                        } else if (params.x > screenW - dp(68) - dp(60)) {
                            edgeSide = 1;
                            prefs.edit().putInt(KEY_EDGE, 1).apply();
                            scheduleHide(1000);
                        } else {
                            edgeSide = -1;
                            prefs.edit().putInt(KEY_EDGE, -1).apply();
                            savePos();
                        }
                        dragging = false;
                        return true;
                    }
                    // 点击：按位置区分主按钮(上)/模式按钮(下)（官方风格立即响应）
                    float relY = event.getRawY() - params.y;
                    if (relY < dp(56)) {
                        toggleMain();   // 主按钮：2D↔3D
                    } else {
                        cycleMode();    // 模式按钮：HSBS/FSBS/2D3D
                    }
                    return true;
            }
            return false;
        }
    };


    // ============ 公共：拖动/收缩/唤出 ============
    private boolean handleBarTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                dragging = false;
                slidingOut = false;
                handler.removeCallbacks(hideRunnable);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!expanded) {
                    if (Math.abs(dx) > 40 && !slidingOut) slidingOut = true;
                    return true;
                }
                if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                    dragging = true;
                    params.x = startX + (int) dx;
                    params.y = startY + (int) dy;
                    if (params.x < 0) params.x = 0;
                    if (params.x > screenW - barW) params.x = screenW - barW;
                    if (params.y < 0) params.y = 0;
                    if (params.y > screenH - barH) params.y = screenH - barH;
                    wm.updateViewLayout(curView, params);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!expanded) {
                    // 收缩态：滑动或点击都唤出
                    expandBar();
                    slidingOut = false;
                    return true;
                }
                if (dragging) {
                    if (params.x < dp(60)) {
                        edgeSide = 0;
                        prefs.edit().putInt(KEY_EDGE, 0).apply();
                        scheduleHide(1000);
                    } else if (params.x > screenW - barW - dp(60)) {
                        edgeSide = 1;
                        prefs.edit().putInt(KEY_EDGE, 1).apply();
                        scheduleHide(1000);
                    } else {
                        edgeSide = -1;
                        prefs.edit().putInt(KEY_EDGE, -1).apply();
                        savePos();
                    }
                    dragging = false;
                }
                return true;
        }
        return false;
    }

    private void savePos() {
        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply();
    }

    private void scheduleHide(int delayMs) {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, delayMs);
    }

    private Runnable hideRunnable = new Runnable() {
        @Override
        public void run() { shrinkToEdge(); }
    };

    /** 收缩成边缘触摸条 */
    private void shrinkToEdge() {
        if (!shown || !expanded) return;
        expanded = false;
        int targetX = (edgeSide == 0) ? 0 : (screenW - edgeW);
        int targetY = params.y;
        if (targetY > screenH - edgeH) targetY = screenH - edgeH;
        params.x = targetX;
        params.y = targetY;
        params.width = edgeW;
        params.height = edgeH;
        if (currentType == 1) {
            barContent.setVisibility(View.GONE);
            barContainer.setBackgroundColor(0x00000000);
            barEdgeHint.setVisibility(View.VISIBLE);
            android.view.ViewGroup.LayoutParams lp = barEdgeHint.getLayoutParams();
            lp.width = Math.max(3, edgeW / 5);   // 触摸区的 1/5
            barEdgeHint.setLayoutParams(lp);
            ((android.widget.FrameLayout.LayoutParams) barEdgeHint.getLayoutParams()).gravity =
                    (edgeSide == 0) ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT;
        } else if (currentType == 2) {
            // 按钮收缩：隐藏两个按钮本体，只显示 2.5dp 白色细条
            btnView.setBackgroundColor(0x00000000);
            btnView.findViewById(R.id.btn_container)
                    .setBackgroundResource(android.R.color.transparent);
            btnView.findViewById(R.id.btn_main).setVisibility(View.GONE);
            btnView.findViewById(R.id.btn_mode).setVisibility(View.GONE);
            btnView.findViewById(R.id.btn_viewpoint).setVisibility(View.GONE);
            tvBtnText.setVisibility(View.GONE);
            tvModeText.setVisibility(View.GONE);
            tvVpText.setVisibility(View.GONE);
            btnEdgeHint.setVisibility(View.VISIBLE);
            android.view.ViewGroup.LayoutParams lp2 = btnEdgeHint.getLayoutParams();
            lp2.width = Math.round(2.5f * getResources().getDisplayMetrics().density); // 2.5dp
            btnEdgeHint.setLayoutParams(lp2);
            ((android.widget.LinearLayout.LayoutParams) btnEdgeHint.getLayoutParams()).gravity =
                    (edgeSide == 0) ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT;
        }
        wm.updateViewLayout(curView, params);
        savePos();
    }

    /** 滑动唤出 */
    private void expandBar() {
        if (expanded) return;
        expanded = true;
        int sx = (edgeSide == 0) ? 0 : (screenW - (currentType == 2 ? dp(68) : barW));
        int sy = params.y;
        int w = (currentType == 2) ? dp(68) : barW;
        int h = (currentType == 2) ? dp(124) : barH;
        if (sy > screenH - h) sy = screenH - h;
        params.x = sx;
        params.y = sy;
        params.width = w;
        params.height = h;
        if (currentType == 1) {
            barEdgeHint.setVisibility(View.GONE);
            barContainer.setBackgroundResource(R.drawable.bar_bg);
            barContent.setVisibility(View.VISIBLE);
        } else if (currentType == 2) {
            btnView.setBackgroundColor(0x00000000);
            btnView.findViewById(R.id.btn_container)
                    .setBackgroundResource(R.drawable.btn_frame);
            btnView.findViewById(R.id.btn_main).setVisibility(View.VISIBLE);
            btnView.findViewById(R.id.btn_mode).setVisibility(View.VISIBLE);
            btnView.findViewById(R.id.btn_viewpoint).setVisibility(View.VISIBLE);
            btnEdgeHint.setVisibility(View.GONE);
            tvBtnText.setVisibility(View.VISIBLE);
            tvBtnText.setTextSize(16);
            tvModeText.setVisibility(View.VISIBLE);
        }
        wm.updateViewLayout(curView, params);
        scheduleHide(3000);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        try {
            startService(new Intent(getApplicationContext(), FloatBarService.class));
        } catch (Exception e) { }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(sizeMonitor);
        handler.removeCallbacks(monitor);
        try { unregisterReceiver(barToggleReceiver); } catch (Exception e) { }
        if (shown) {
            try { wm.removeView(curView); } catch (Exception e) { }
            shown = false;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String getProp(String name) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", name});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String v = br.readLine();
            p.waitFor();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private void setDepth(int d) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"setprop", "persist.sys.3deffect", String.valueOf(d)});
            p.waitFor();
        } catch (Exception e) { }
    }
}
