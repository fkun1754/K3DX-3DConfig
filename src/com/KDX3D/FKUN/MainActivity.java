package com.KDX3D.FKUN;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KDX3D游戏配置 (K3DX-V5G 裸眼3D)
 * 机制（实验证实）:
 *  - .gles.cfg 32字节串 = 游戏识别令牌，libGLES 校验完整串，改任何字节都失效
 *    → 只能用官方预生成的完整串（换包名），不能自定义参数
 *  - 识别不挑游戏类型（任何官方串都能通过），但视觉匹配挑：
 *    深度场景型(eye≤30) 适合3D自由视角游戏；平面分层型(eye≥90) 适合固定视角/2D
 *  - 写入 /storage/emulated/0/.gles.cfg 普通权限即可（无需root）
 *  - 保存后需重启游戏才生效（libGLES 启动时读取）
 */
public class MainActivity extends Activity {

    private static final String TAG = "KDX3DConfig";
    private static final String CFG_NAME = ".gles.cfg";
    private static final String CFG_DIR = "/storage/emulated/0";

    // 参考库: {包名, 显示名, 完整官方base64串}
    private ListView listView;
    private List<AppInfo> appList = new ArrayList<AppInfo>();
    private AppAdapter adapter;
    private Map<String, byte[]> cfgMap = new LinkedHashMap<String, byte[]>();
    private final Map<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private List<AppInfo> allApps = new ArrayList<AppInfo>();   // 完整列表（搜索过滤用）
    private String searchKey = "";

    /** 配置文件（App 应用文件夹 config 目录：覆盖更新保留，写入无权限问题） */
    private static final String CONFIG_FILE =
            "/data/data/com.KDX3D.FKUN/files/config/kdx3d_config.json";

    /** 从配置文件恢复所有配置到 prefs（覆盖更新后配置不丢） */
    private void loadConfigFromFile() {
        try {
            java.io.File f = new java.io.File(CONFIG_FILE);
            if (!f.exists()) return;
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            fis.read(buf);
            fis.close();
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, "UTF-8"));
            SharedPreferences.Editor ed =
                    getSharedPreferences("floatbar", MODE_PRIVATE).edit();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object val = o.opt(k);
                if (val instanceof Integer) ed.putInt(k, ((Integer) val).intValue());
                else if (val instanceof Boolean) ed.putBoolean(k, ((Boolean) val).booleanValue());
                else if (val instanceof String) ed.putString(k, (String) val);
                else if (val instanceof Long) ed.putLong(k, ((Long) val).longValue());
            }
            ed.apply();
            Log.i(TAG, "已从配置文件恢复: " + CONFIG_FILE);
        } catch (Exception e) {
            Log.e(TAG, "配置文件读取失败", e);
        }
    }

    /** 保存所有 App 配置到配置文件 */
    private void saveConfigToFile() {
        try {
            SharedPreferences prefs = getSharedPreferences("floatbar", MODE_PRIVATE);
            // getAll() 会阻塞等待 SharedPreferences 文件异步加载完成（避免启动时读到空）
            java.util.Map<String, ?> all = prefs.getAll();
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("app3d_") || k.startsWith("fb_") || k.startsWith("depth_")
                        || k.startsWith("tmpl_") || k.equals("fb_all")
                        || k.equals("guard_enabled")) {
                    o.put(k, e.getValue());
                }
            }
            if (o.length() == 0) return;
            java.io.File f = new java.io.File(CONFIG_FILE);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "配置文件保存失败", e);
        }
    } // pkg -> 32字节
    private Map<String, Integer> pkgSelIdx = new LinkedHashMap<String, Integer>(); // pkg -> 保存时选的模板索引
    private boolean showSystem = false;

    private Drawable fallbackIcon = null;

    static class AppInfo {
        String pkg;
        String label;
        Drawable icon;
        boolean hasCfg;    // 3D游戏（.gles.cfg）
        boolean hasApp3d;  // 3D应用（SBS转立体）
    }

    private int lastOrientation = -1;

    /** 主界面 UI 绑定（onCreate 与方向变化时调用，数据保留） */
    private void initUI() {
        listView = (ListView) findViewById(R.id.list_apps);
        adapter = new AppAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppInfo app = appList.get(position);
                // 已有配置：直接进入对应配置界面；未配置/双配置 → 弹类型选择窗
                if (app.hasCfg && !app.hasApp3d) {
                    showParamDialog(app);
                } else if (app.hasApp3d && !app.hasCfg) {
                    showApp3dDialog(app);
                } else {
                    showTypeDialog(app);
                }
            }
        });

        // 搜索框：实时过滤应用列表
        final android.widget.EditText etSearch = (android.widget.EditText) findViewById(R.id.et_search);
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                searchKey = e.toString();
                appList = filterApps(allApps);
                adapter.notifyDataSetChanged();
            }
        });

        // 显示系统应用开关
        Switch swShow = (Switch) findViewById(R.id.sw_show_system);
        swShow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                showSystem = checked;
                loadApps();
            }
        });

        // 进程守护开关（Magisk 模块：开机自启 + 被杀自动恢复）
        final Switch swGuard = (Switch) findViewById(R.id.sw_guard);
        swGuard.setChecked(getSharedPreferences("floatbar", MODE_PRIVATE)
                .getBoolean("guard_enabled", false));
        swGuard.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) {
                    // 进程守护需要 root（Magisk 授权）
                    if (!isRoot()) {
                        b.setChecked(false);
                        exportGuardZip();
                        new AlertDialog.Builder(MainActivity.this, R.style.AppDialog)
                                .setTitle("需要 root 权限")
                                .setMessage("进程守护通过 Magisk 模块实现，需要 root 权限。\n未获取到 root，模块已导出到 /storage/emulated/0/kdx3d_guard.zip\n可稍后在 Magisk 管理器 → 模块 → 从本地安装 手动刷入。")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    boolean ok = installGuardModule();
                    if (ok) {
                        getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                                .putBoolean("guard_enabled", true).apply();
                        Toast.makeText(MainActivity.this, "守护模块已刷入，重启后生效", Toast.LENGTH_LONG).show();
                    } else {
                        b.setChecked(false);
                        exportGuardZip();
                        new AlertDialog.Builder(MainActivity.this, R.style.AppDialog)
                                .setTitle("需要手动刷入模块")
                                .setMessage("自动刷入失败。\n模块已导出到 /storage/emulated/0/kdx3d_guard.zip\n请在 Magisk 管理器 → 模块 → 从本地安装 选择它。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                } else {
                    getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                            .putBoolean("guard_enabled", false).apply();
                    // 尝试移除模块（守护循环检测到开关关闭后自然停止）
                    suExec("rm -rf /data/adb/modules/kdx3d_guard");
                    Toast.makeText(MainActivity.this, "守护已关闭（模块已移除）", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 下拉刷新
        setupPullRefresh();

        // 从配置文件恢复所有配置（覆盖更新后配置不丢）
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 方向变化：重新加载主界面布局（自适应横竖屏，数据保留）
        if (newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation;
            setContentView(R.layout.activity_main);
            initUI();
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        lastOrientation = getResources().getConfiguration().orientation;
        loadConfigFromFile();
        // 启动即同步一次（创建/更新配置文件，确保持久化）
        saveConfigToFile();
        loadCfg();
        loadApps();

        // 悬浮窗服务常驻（显示与否由各应用配置控制）
        startService(new Intent(this, FloatBarService.class));

        // 启动时申请 root（Magisk 授权弹窗），用于进程守护/后台驻留（努比亚白名单）
        if (isRoot()) {
            addToWhitelist();
            // 自动设置 SELinux 宽松（游戏深度调节 setprop 需要）
            suExec("setenforce 0");
            Log.i(TAG, "已自动设置 SELinux 宽松");
        }

        // 打开应用时获取权限：悬浮窗 + 后台运行（只一次）+ 存储 + 电池优化 + 通知（分版本）
        if (!hasOverlayPermission()) {
            requestOverlayPermission();
        }
        requestStoragePermission();
        requestBatteryOptimization();
        requestNotificationPermission();
        requestBackgroundPermission();
    }

    /** 存储权限（向上适配）：
     *  - Android 11+：需要 MANAGE_EXTERNAL_STORAGE（所有文件访问，写根目录 .gles.cfg 必需）
     *  - Android 6-10：WRITE/READ_EXTERNAL_STORAGE 运行时权限 */
    private void requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                if (!android.os.Environment.isExternalStorageManager()) {
                    Intent i = new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) { }
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                    new String[]{
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        }
    }

    /** 电池优化（Android 6+ 后台驻留防杀） */
    private void requestBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager)
                        getSystemService(POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception e) { }
        }
    }

    /** 通知权限（Android 13+） */
    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
        }
    }

    /** 检查 root 可用（触发 Magisk 授权弹窗） */
    private boolean isRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** su 执行命令（返回 exit code） */
    private boolean suExec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 检测 SELinux 是否 Enforcing（游戏深度调节 setprop 需要宽松模式） */
    private boolean isSelinuxEnforcing() {
        try {
            java.io.File f = new java.io.File("/sys/fs/selinux/enforce");
            if (f.exists()) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(f)));
                String v = br.readLine();
                br.close();
                return v != null && v.trim().equals("1");
            }
        } catch (Exception e) { }
        return false;   // 读不到/不存在：视为不强制（多数设备默认可读）
    }

    /**
     * 加入努比亚 TaskManager 白名单（root）：
     * 进程不被清理 + 开机自启不被拦截
     */
    private boolean addToWhitelist() {
        return modifyWhitelist(true);
    }

    /** 从努比亚白名单移除（root） */
    private boolean removeFromWhitelist() {
        return modifyWhitelist(false);
    }

    private boolean modifyWhitelist(boolean add) {
        try {
            String src = "/data/data/cn.nubia.processmanager/databases/process_white.db";
            String tmp = getFilesDir() + "/pw.db";
            // 1. su 复制 db 到私有目录
            if (!suExec("cp " + src + " " + tmp + " && chmod 666 " + tmp)) return false;
            // 2. Java 修改
            android.database.sqlite.SQLiteDatabase db =
                    android.database.sqlite.SQLiteDatabase.openDatabase(tmp, null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            android.database.Cursor c = db.rawQuery(
                    "SELECT 1 FROM list WHERE package_name=?",
                    new String[]{getPackageName()});
            boolean exists = c.moveToFirst();
            c.close();
            if (add && !exists) {
                db.execSQL("INSERT INTO list (package_name, pss_threshold, kill_mode, instance_id) VALUES (?,100,0,0)",
                        new Object[]{getPackageName()});
            } else if (!add && exists) {
                db.execSQL("DELETE FROM list WHERE package_name=?", new Object[]{getPackageName()});
            }
            db.close();
            // 3. su 复制回去
            return suExec("cp " + tmp + " " + src + " && chmod 666 " + src);
        } catch (Exception e) {
            android.util.Log.e(TAG, "白名单操作失败", e);
            return false;
        }
    }

    /** 悬浮窗权限检查（API 22 用 AppOps，反射兼容新旧签名） */
    private boolean hasOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        try {
            Class<?> cls = Class.forName("android.app.AppOpsManager");
            Object aom = getSystemService("appops");
            int myUid = android.os.Process.myUid();
            String pkg = getPackageName();
            java.lang.reflect.Method m;
            int mode;
            try {
                m = cls.getMethod("checkOpNoThrow", int.class, int.class, String.class);
                mode = (Integer) m.invoke(aom, 24, myUid, pkg); // OP_SYSTEM_ALERT_WINDOW=24
            } catch (NoSuchMethodException e) {
                m = cls.getMethod("checkOpNoThrow", String.class, int.class, String.class);
                mode = (Integer) m.invoke(aom, "SYSTEM_ALERT_WINDOW", myUid, pkg);
            }
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return true; // 检查不了视为已授权（manifest 已声明）
        }
    }

    /** 跳转悬浮窗权限设置页 */
    private void openOverlaySettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                Intent it = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(it);
            } else {
                Intent it = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(it);
            }
        } catch (Exception e) {
            Toast.makeText(this, "请到 设置→应用 中手动开启悬浮窗权限", Toast.LENGTH_LONG).show();
        }
    }

    private void enableFloatBar() {
        getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                .putBoolean("enabled", true).apply();
        startService(new Intent(MainActivity.this, FloatBarService.class));
        Toast.makeText(MainActivity.this, "3D深度悬浮窗已开启", Toast.LENGTH_SHORT).show();
        // root：加入努比亚白名单（后台驻留+自启），失败则提示
        if (isRoot()) {
            if (!addToWhitelist()) {
                Toast.makeText(this, "已获取root，但白名单写入失败", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已加入后台驻留白名单（root）", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 检查"使用情况访问"权限（OP_GET_USAGE_STATS=43） */
    private boolean hasUsageStatsPermission() {
        try {
            Class<?> cls = Class.forName("android.app.AppOpsManager");
            Object aom = getSystemService("appops");
            int myUid = android.os.Process.myUid();
            java.lang.reflect.Method m;
            int mode;
            try {
                m = cls.getMethod("checkOpNoThrow", int.class, int.class, String.class);
                mode = (Integer) m.invoke(aom, 43, myUid, getPackageName());
            } catch (NoSuchMethodException e) {
                m = cls.getMethod("checkOpNoThrow", String.class, int.class, String.class);
                mode = (Integer) m.invoke(aom, "GET_USAGE_STATS", myUid, getPackageName());
            }
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 引导开启"使用情况访问"（前台应用检测需要） */
    private void requestUsageStatsPermission() {
        if (hasUsageStatsPermission()) return;
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("需要「使用情况访问」权限")
                .setMessage("悬浮条需要检测前台应用才能自动显示/隐藏。\n点击确定后在设置中允许「使用情况访问」。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "请到 设置→安全 中允许使用情况访问", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 引导开启后台运行权限（只在第一次打开时提示） */
    private void requestBackgroundPermission() {
        final SharedPreferences sp = getSharedPreferences("floatbar", MODE_PRIVATE);
        if (sp.getBoolean("bg_prompted", false)) return;
        sp.edit().putBoolean("bg_prompted", true).apply();
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("允许后台运行")
                .setMessage("为保证悬浮条在游戏中不被系统清理，请在应用设置中允许「自启动/后台运行」。\n点击确定前往设置。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            Intent it = new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(it);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "请到 设置→应用 中允许后台运行", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveConfigToFile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 使用情况访问权限：没授权就继续申请（每次回到应用都检查）
        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission();
        }
    }

    /** 悬浮窗权限引导（打开应用时申请） */
    private void requestOverlayPermission() {
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("需要悬浮窗权限")
                .setMessage("3D深度悬浮窗需要悬浮窗权限才能在游戏/应用上方显示。\n点击确定后请在设置中允许「显示在其他应用上层」。")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openOverlaySettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 刷入 Magisk 守护模块（root）：提取 assets zip → /storage/emulated/0 → magisk --install-module */
    private boolean installGuardModule() {
        try {
            // 1. 提取 assets zip 到 /storage/emulated/0
            File out = new File("/storage/emulated/0/kdx3d_guard.zip");
            java.io.InputStream is = getAssets().open("kdx3d_guard.zip");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();
            // 2. magisk 刷入
            return suExec("magisk --install-module /storage/emulated/0/kdx3d_guard.zip");
        } catch (Exception e) {
            android.util.Log.e(TAG, "刷入守护模块失败", e);
            return false;
        }
    }

    /** 导出模块 zip 到 /storage/emulated/0（供手动刷入） */
    private void exportGuardZip() {
        try {
            java.io.InputStream is = getAssets().open("kdx3d_guard.zip");
            java.io.FileOutputStream fos =
                    new java.io.FileOutputStream(new File("/storage/emulated/0/kdx3d_guard.zip"));
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();
        } catch (Exception e) {
            android.util.Log.e(TAG, "导出模块失败", e);
        }
    }

    /** 下拉刷新：列表在顶部时下拉超过阈值触发重新加载 */
    private void setupPullRefresh() {
        final float[] downY = new float[1];
        final boolean[] pulled = new boolean[1];
        listView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downY[0] = event.getY();
                        pulled[0] = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!pulled[0] && listView.getFirstVisiblePosition() == 0
                                && event.getY() - downY[0] > 120) {
                            pulled[0] = true;
                            Toast.makeText(MainActivity.this, "刷新中...", Toast.LENGTH_SHORT).show();
                            loadCfg();
                            loadApps();
                        }
                        break;
                }
                return false;
            }
        });
    }

    /** 读取 /storage/emulated/0/.gles.cfg */
    private void loadCfg() {
        cfgMap.clear();
        // 确保配置文件存在（没有则创建，满足 3D 游戏配置需求）
        File f = new File(CFG_DIR, CFG_NAME);
        if (!f.exists()) {
            try {
                FileOutputStream fos = new FileOutputStream(f);
                fos.write("#package_name   enableMix,eye_angle,focus_plane_pos,near_plane_pos,left_right_exchange\n"
                        .getBytes("UTF-8"));
                fos.close();
            } catch (Exception e) {
                android.util.Log.e(TAG, "创建 .gles.cfg 失败", e);
            }
        }
        if (!f.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                try {
                    byte[] raw = Base64.decode(parts[1], Base64.DEFAULT);
                    if (raw.length == 32) cfgMap.put(parts[0], raw);
                } catch (Exception e) { }
            }
            br.close();
            Log.i(TAG, "已加载 " + cfgMap.size() + " 条配置");
        } catch (Exception e) {
            Log.e(TAG, "读配置失败", e);
        }
    }

    /** 列出应用（按开关决定是否含系统应用） */
    private void loadApps() {
        new AsyncTask<Void, Void, List<AppInfo>>() {
            @Override
            protected List<AppInfo> doInBackground(Void... params) {
                List<AppInfo> result = new ArrayList<AppInfo>();
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo ai : apps) {
                    if (!showSystem && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if (ai.packageName.equals("com.KDX3D.FKUN")) continue;
                    AppInfo info = new AppInfo();
                    info.pkg = ai.packageName;
                    try {
                        info.label = pm.getApplicationLabel(ai).toString();
                    } catch (Exception e) {
                        info.label = info.pkg;
                    }
                    // icon 缓存复用（避免每次全量重解码导致卡顿）
                    Drawable cachedIcon = iconCache.get(ai.packageName);
                    if (cachedIcon != null) {
                        info.icon = cachedIcon;
                    } else {
                        try {
                            info.icon = pm.getApplicationIcon(ai);
                            iconCache.put(ai.packageName, info.icon);
                        } catch (Exception e) {
                            info.icon = null;
                        }
                    }
                    info.hasCfg = cfgMap.containsKey(info.pkg);
                    info.hasApp3d = getSharedPreferences("floatbar", MODE_PRIVATE)
                            .getString("app3d_" + info.pkg, null) != null;
                    result.add(info);
                }
                java.util.Collections.sort(result, new java.util.Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        // 排序：3D游戏靠前 > 3D应用 > 未设置应用最后
                        int sa = a.hasCfg ? 0 : (a.hasApp3d ? 1 : 2);
                        int sb = b.hasCfg ? 0 : (b.hasApp3d ? 1 : 2);
                        if (sa != sb) return sa - sb;
                        return a.label.compareTo(b.label);
                    }
                });
                allApps = result;
                return filterApps(result);
            }

            @Override
            protected void onPostExecute(List<AppInfo> list) {
                appList.clear();
                appList.addAll(list);
                adapter.notifyDataSetChanged();
            }
        }.execute();
    }

    /** 按搜索关键词过滤应用列表 */
    private List<AppInfo> filterApps(List<AppInfo> src) {
        if (searchKey == null || searchKey.length() == 0) return src;
        List<AppInfo> out = new ArrayList<AppInfo>();
        String k = searchKey.toLowerCase();
        for (AppInfo a : src) {
            if (a.label.toLowerCase().contains(k) || a.pkg.toLowerCase().contains(k)) {
                out.add(a);
            }
        }
        return out;
    }

    /** 类型选择弹窗：单选类型，勾选即保存默认并直接进入对应配置界面 */
    private void showTypeDialog(final AppInfo app) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_type, null);
        TextView tvTitle = (TextView) v.findViewById(R.id.tv_type_title);
        tvTitle.setText(app.label + "\n" + app.pkg);
        final RadioButton cbGame = (RadioButton) v.findViewById(R.id.cb_game);
        final RadioButton cbApp = (RadioButton) v.findViewById(R.id.cb_app);
        // 已有配置的项选中（单选互斥）
        if (app.hasCfg && app.hasApp3d) cbGame.setChecked(true);
        TextView tvHint = (TextView) v.findViewById(R.id.tv_type_hint);
        tvHint.setText("选择类型后进入对应配置界面。\n3D游戏：.gles.cfg 识别串 + 3D深度\n3D应用：SBS画面转立体（视角/自动3D/悬浮按钮）\n删除配置后重新选择类型");

        final AlertDialog typeDlg = new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("选择3D类型")
                .setView(v)
                .setPositiveButton("关闭", null)
                .create();

        // 勾选 3D游戏 → 保存默认 + 直接进游戏配置
        cbGame.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) {
                    if (!app.hasCfg) saveDefaultGameCfg(app);
                    typeDlg.dismiss();
                    showParamDialog(app);
                } else if (!app.hasCfg && app.hasApp3d) {
                    // 取消勾选（切到应用时）→ 已有应用配置则不动
                }
            }
        });
        // 勾选 3D应用 → 保存默认 + 直接进应用配置
        cbApp.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) {
                    if (!app.hasApp3d) saveDefaultApp3d(app);
                    typeDlg.dismiss();
                    showApp3dDialog(app);
                }
            }
        });

        typeDlg.show();
    }

    /** 默认推荐模板：水果忍者（实测多数游戏效果最佳，eye=100 通用性最强） */
    private static final String DEFAULT_TMPL =
            "S0RYHHhaxusB/2QAV1dkAAAAAAAAAAAAAAAAAAAAAAA=";

    /** 找默认模板索引 */
    private int findDefaultIdx() {
        String[][] lib = buildLib();
        for (int i = 0; i < lib.length; i++) {
            if (DEFAULT_TMPL.equals(lib[i][2])) return i;
        }
        return 0;
    }

    /** 根据已有配置串反查模板索引（无记忆时显示对应模板名称） */
    private int matchTemplateIdx(byte[] cur) {
        if (cur == null) return 0;   // 无配置：默认深度场景型
        String[][] lib = buildLib();
        for (int i = 0; i < lib.length; i++) {
            if (lib[i][2] != null && lib[i][2].length() > 0) {
                try {
                    byte[] t = Base64.decode(lib[i][2], Base64.DEFAULT);
                    if (java.util.Arrays.equals(cur, t)) return i;
                } catch (Exception e) { }
            }
        }
        return 0;   // 未匹配（自定义配置）：默认深度场景型
    }

    /** 勾选 3D游戏：按默认模板（深度场景型第一个官方串）写入 .gles.cfg */
    private void saveDefaultGameCfg(AppInfo app) {
        String[][] lib = buildLib();
        String[] def = null;
        for (String[] t : lib) {
            if (DEFAULT_TMPL.equals(t[2])) { def = t; break; }   // 默认深度·盗墓笔记
        }
        if (def == null) return;
        try {
            byte[] raw = Base64.decode(def[2], Base64.DEFAULT);
            if (writeCfg(app.pkg, raw)) {
                cfgMap.put(app.pkg, raw);
                int idx = -1;
                for (int i = 0; i < lib.length; i++) {
                    if (lib[i] == def) { idx = i; break; }
                }
                pkgSelIdx.put(app.pkg, idx);
                getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                        .putInt("tmpl_" + app.pkg, idx)
                        .putInt("depth_" + app.pkg, 12)   // 默认3D深度 12
                        .apply();
                app.hasCfg = true;
                adapter.notifyDataSetChanged();
                saveConfigToFile();
                Toast.makeText(this, "已按默认配置保存3D游戏（" + def[1] + "，深度12）", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "默认游戏配置保存失败", e);
        }
    }

    /** 勾选 3D应用：按默认配置保存（视角2/不自动/HSBS/开启悬浮按钮） */
    private void saveDefaultApp3d(AppInfo app) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("viewpoint", 2);
            o.put("auto", 0);
            o.put("mode", 0);
            o.put("btn", 1);
            o.put("showMode", 1);
            o.put("showVp", 1);
            getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                    .putString("app3d_" + app.pkg, o.toString()).apply();
            app.hasApp3d = true;
            adapter.notifyDataSetChanged();
            // 立即应用默认视角2（写 .3d.properties + send2sf）
            applyViewPoint(2);
            saveConfigToFile();
            Toast.makeText(this, "已按默认配置保存3D应用（视角2）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e(TAG, "默认应用配置保存失败", e);
        }
    }

    /** 3D应用配置窗口：视角 + 自动3D模式 + 悬浮按钮 */
    private void showApp3dDialog(final AppInfo app) {
        final View v = LayoutInflater.from(this).inflate(R.layout.dialog_app3d, null);
        TextView tvTitle = (TextView) v.findViewById(R.id.tv_app3d_title);
        tvTitle.setText(app.label + "\n" + app.pkg);

        final SharedPreferences prefs = getSharedPreferences("floatbar", MODE_PRIVATE);
        // 读取已有配置（JSON: viewpoint/auto/mode/btn）
        int viewpoint = 2, auto = 0, mode = 0, btn = 1;   // 默认视角2
        int showMode = 1, showVp = 1;
        try {
            String s = prefs.getString("app3d_" + app.pkg, null);
            if (s != null) {
                org.json.JSONObject o = new org.json.JSONObject(s);
                viewpoint = o.optInt("viewpoint", 1);
                auto = o.optInt("auto", 0);
                mode = o.optInt("mode", 0);
                btn = o.optInt("btn", 1);
                showMode = o.optInt("showMode", 1);
                showVp = o.optInt("showVp", 1);
            }
        } catch (Exception e) { }

        final Spinner spVp = (Spinner) v.findViewById(R.id.sp_vp);
        ArrayAdapter<String> vpAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"右左", "左右"});
        vpAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVp.setAdapter(vpAdapter);
        spVp.setSelection(viewpoint == 1 ? 0 : 1);

        final Switch swAuto = (Switch) v.findViewById(R.id.sw_auto3d);
        final Spinner spMode = (Spinner) v.findViewById(R.id.sp_auto_mode);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"HSBS", "FSBS", "2D转3D"});
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(modeAdapter);
        spMode.setSelection(mode);
        swAuto.setChecked(auto == 1);

        final Switch swBtn = (Switch) v.findViewById(R.id.sw_app_btn);
        swBtn.setChecked(btn == 1);
        // 子选项（模式/视角按钮开关）：仅开启悬浮按钮后显示
        final View llSubOpts = v.findViewById(R.id.ll_sub_opts);
        final Switch swShowMode = (Switch) v.findViewById(R.id.sw_show_mode);
        final Switch swShowVp = (Switch) v.findViewById(R.id.sw_show_vp);
        swShowMode.setChecked(showMode == 1);
        swShowVp.setChecked(showVp == 1);
        llSubOpts.setVisibility(btn == 1 ? View.VISIBLE : View.GONE);
        swBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                llSubOpts.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
        });

        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("3D应用设置")
                .setView(v)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int vp = spVp.getSelectedItemPosition() == 0 ? 1 : 2;
                        int autoV = swAuto.isChecked() ? 1 : 0;
                        int modeV = spMode.getSelectedItemPosition();
                        int btnV = swBtn.isChecked() ? 1 : 0;
                        // 保存配置
                        try {
                            org.json.JSONObject o = new org.json.JSONObject();
                            o.put("viewpoint", vp);
                            o.put("auto", autoV);
                            o.put("mode", modeV);
                            o.put("btn", btnV);
                            o.put("showMode", swShowMode.isChecked() ? 1 : 0);
                            o.put("showVp", swShowVp.isChecked() ? 1 : 0);
                            prefs.edit().putString("app3d_" + app.pkg, o.toString()).apply();
                        } catch (Exception e) { }
                        // 应用视角（写 .3d.properties + send2sf 视点）
                        applyViewPoint(vp);
                        // 若开启自动3D：立即生效
                        if (autoV == 1) {
                            com.wztech.service3d.Service3D.set3DEnabled(true);
                            com.wztech.service3d.Service3D.setDisplayMode(modeV);
                        } else {
                            com.wztech.service3d.Service3D.set3DEnabled(false);
                        }
                        app.hasApp3d = true;
                        adapter.notifyDataSetChanged();
                                saveConfigToFile();
                        Toast.makeText(MainActivity.this, "3D应用配置已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("删除配置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().remove("app3d_" + app.pkg).apply();
                        app.hasApp3d = false;
                        adapter.notifyDataSetChanged();
                                com.wztech.service3d.Service3D.set3DEnabled(false);
                        saveConfigToFile();
                        Toast.makeText(MainActivity.this, "已删除3D应用配置", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 应用视角：写 /storage/emulated/0/K3DX/config/.3d.properties + send2sf */
    private void applyViewPoint(int vp) {
        try {
            // 视角1(右左)=VP01(viewpoint=1)，视角2(左右)=VP02(viewpoint=0)
            java.io.File f = new java.io.File("/storage/emulated/0/K3DX/config/.3d.properties");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            String content = "#WZTECH\nviewpoint=" + (vp == 1 ? "1" : "0") + "\nnavflag=1\n";
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) { }
        // 视点消息：VP01=1, VP02=0
        com.wztech.service3d.Service3D.setViewPoint(vp == 1 ? 1 : 0);
    }

    /** 参数设置弹窗 */
    private void showParamDialog(final AppInfo app) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_params, null);
        TextView tvTitle = (TextView) v.findViewById(R.id.tv_pkg_title);
        tvTitle.setText(app.label + "\n" + app.pkg);

        byte[] cur = cfgMap.get(app.pkg);

        final TextView spRef = (TextView) v.findViewById(R.id.sp_ref);
        final String[][] lib = buildLib();
        final int[] curSelIdx = new int[]{0};

        final TextView tvParam = (TextView) v.findViewById(R.id.tv_param_info);

        // 3D深度悬浮窗开关（按应用记忆，悬浮条服务按此显示）
        final SharedPreferences barPrefs2 = getSharedPreferences("floatbar", MODE_PRIVATE);
        final Switch swGameFb = (Switch) v.findViewById(R.id.sw_game_fb);
        // 全部游戏开启复选框（fb_all 全局）
        final CheckBox cbAllFb = (CheckBox) v.findViewById(R.id.cb_all_fb);
        boolean fbAll = barPrefs2.getBoolean("fb_all", false);
        // 全开时：本游戏开关强制开启且锁定；复选框仅在开关开启时显示
        swGameFb.setChecked(fbAll || barPrefs2.getBoolean("fb_" + app.pkg, false));
        swGameFb.setEnabled(!fbAll);
        cbAllFb.setChecked(fbAll);
        cbAllFb.setVisibility(swGameFb.isChecked() ? View.VISIBLE : View.GONE);

        // 单个开关：开启 → 显示"全部开启"复选框；关闭 → 隐藏并取消全开
        swGameFb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) {
                    cbAllFb.setVisibility(View.VISIBLE);
                    // SELinux 宽松检查（深度调节 setprop 需要）：仅 Enforcing 设备提示，只一次
                    if (isSelinuxEnforcing()) {
                        SharedPreferences sp = getSharedPreferences("floatbar", MODE_PRIVATE);
                        if (!sp.getBoolean("selinux_prompted", false)) {
                            sp.edit().putBoolean("selinux_prompted", true).apply();
                            if (isRoot()) {
                                suExec("setenforce 0");
                                Toast.makeText(MainActivity.this,
                                        "已自动设置 SELinux 宽松，深度调节可用",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                new AlertDialog.Builder(MainActivity.this, R.style.AppDialog)
                                        .setTitle("需要 root 权限")
                                        .setMessage("游戏深度调节需要设置 SELinux 为宽松，需要 root 权限。\n\n"
                                                + "请授予 root 权限后重新开启，App 会自动设置。")
                                        .setPositiveButton("知道了", null)
                                        .show();
                            }
                        }
                    }
                } else {
                    cbAllFb.setVisibility(View.GONE);
                    if (cbAllFb.isChecked()) {
                        cbAllFb.setChecked(false);   // 取消全开，恢复单个控制
                        barPrefs2.edit().putBoolean("fb_all", false).apply();
                    }
                }
            }
        });
        // 全部开启：强制所有游戏开启（单个开关锁定），取消后才恢复单个控制
        cbAllFb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                barPrefs2.edit().putBoolean("fb_all", checked).apply();
                if (checked) {
                    swGameFb.setChecked(true);
                    swGameFb.setEnabled(false);   // 全开期间锁定
                } else {
                    swGameFb.setEnabled(true);    // 取消全开，恢复单个控制
                }
            }
        });

        // 3D深度 (persist.sys.3deffect, 同系统3Dconfig；按应用记忆)
        final SeekBar sbDepth = (SeekBar) v.findViewById(R.id.sb_depth);
        final TextView tvDepth = (TextView) v.findViewById(R.id.tv_depth);
        final SharedPreferences barPrefs = getSharedPreferences("floatbar", MODE_PRIVATE);
        int depth = barPrefs.getInt("depth_" + app.pkg, -1);
        if (depth < 0) {
            try {
                String dv = getProp("persist.sys.3deffect");
                if (dv != null && dv.trim().length() > 0) depth = Integer.parseInt(dv.trim());
                else depth = 10;
            } catch (Exception e) { depth = 10; }
        }
        sbDepth.setProgress(depth);
        tvDepth.setText(String.valueOf(depth));
        sbDepth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvDepth.setText(String.valueOf(p));
                if (fromUser) {
                    setDepth(p);
                    // 记忆该应用深度（与悬浮条同步）
                    barPrefs.edit().putInt("depth_" + app.pkg, p).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // 默认选中：记住上次保存的模板（内存 + 持久化，退出重进仍有效）
        Integer savedIdx = pkgSelIdx.get(app.pkg);
        if (savedIdx == null) {
            int pi = getSharedPreferences("floatbar", MODE_PRIVATE)
                    .getInt("tmpl_" + app.pkg, -1);
            if (pi >= 0) savedIdx = pi;
        }
        curSelIdx[0] = savedIdx != null ? savedIdx
                : (cur != null ? matchTemplateIdx(cur) : findDefaultIdx());
        spRef.setText(lib[curSelIdx[0]][1]);

        // 快速匹配提示
        TextView tvTip = (TextView) v.findViewById(R.id.tv_match_tip);
        tvTip.setText("默认模板为多数游戏通用参数，效果不佳可在上方列表中更换");

        spRef.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View vv) {
                // 模板选择+搜索对话框
                View dv = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.dialog_template_search, null);
                final android.widget.EditText et = (android.widget.EditText)
                        dv.findViewById(R.id.et_tmpl_search);
                final ListView lv = (ListView) dv.findViewById(R.id.lv_tmpl);
                final String[] names = new String[lib.length];
                for (int i = 0; i < lib.length; i++) names[i] = lib[i][1];
                final ArrayAdapter<String> ad = new ArrayAdapter<String>(
                        MainActivity.this, android.R.layout.simple_list_item_1, names);
                lv.setAdapter(ad);
                lv.setSelection(curSelIdx[0]);
                et.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                    @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
                    @Override public void afterTextChanged(android.text.Editable e) {
                        String k = e.toString().trim().toLowerCase();
                        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
                        for (String n : names) {
                            if (k.length() == 0 || n.toLowerCase().contains(k)) out.add(n);
                        }
                        ad.clear();
                        for (String n : out) ad.add(n);
                        ad.notifyDataSetChanged();
                    }
                });
                final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("选择参考模板（" + lib.length + " 条）")
                        .setView(dv)
                        .setNegativeButton("取消", null)
                        .create();
                lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                        String selName = (String) parent.getItemAtPosition(pos);
                        for (int i = 0; i < lib.length; i++) {
                            if (lib[i][1].equals(selName)) {
                                curSelIdx[0] = i;
                                spRef.setText(lib[i][1]);
                                updateParamInfo(lib[i], tvParam);
                                break;
                            }
                        }
                        dlg.dismiss();
                    }
                });
                dlg.show();
            }
        });
        updateParamInfo(lib[curSelIdx[0]], tvParam);

        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("3D游戏设置")
                .setView(v)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String[] sel = lib[curSelIdx[0]];
                        if (sel[2] == null || sel[2].length() == 0) {
                            Toast.makeText(MainActivity.this, "请先选择参考模板", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        byte[] raw = Base64.decode(sel[2], Base64.DEFAULT);
                        boolean ok = writeCfg(app.pkg, raw);
                        if (ok) {
                            cfgMap.put(app.pkg, raw);
                            pkgSelIdx.put(app.pkg, curSelIdx[0]);
                            getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                                    .putInt("tmpl_" + app.pkg, curSelIdx[0]).apply();
                            // 保存 3D深度悬浮窗开关状态 + 全部开启状态
                            getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                                    .putBoolean("fb_" + app.pkg, swGameFb.isChecked())
                                    .putBoolean("fb_all", cbAllFb.isChecked()).apply();
                            app.hasCfg = true;
                            adapter.notifyDataSetChanged();
                                        saveConfigToFile();
                            Toast.makeText(MainActivity.this, "已写入，重启游戏后生效", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "写入失败！", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("删除配置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        boolean ok = removeCfg(app.pkg);
                        if (ok) {
                            cfgMap.remove(app.pkg);
                            pkgSelIdx.remove(app.pkg);
                            getSharedPreferences("floatbar", MODE_PRIVATE).edit()
                                    .remove("tmpl_" + app.pkg).apply();
                            app.hasCfg = false;
                            adapter.notifyDataSetChanged();
                                        saveConfigToFile();
                            Toast.makeText(MainActivity.this, "已删除，重启游戏后生效", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "删除失败", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    /** 显示模板参数信息 */
    private void updateParamInfo(String[] entry, TextView tv) {
        if (entry[2] == null || entry[2].length() == 0) {
            tv.setText("（选择模板后显示参数）");
            return;
        }
        byte[] raw = Base64.decode(entry[2], Base64.DEFAULT);
        tv.setText(String.format("识别参数: 眼距角%d 视差微调%d 聚焦平面%d 近平面%d",
                raw[10] & 0xFF, raw[11] & 0xFF, raw[12] & 0xFF, raw[13] & 0xFF));
    }

    /**
     * 快速匹配提示：根据包名/应用名关键词判断游戏渲染类型
     */
    private String guessType(String pkg, String label) {
        String s = (pkg + " " + label).toLowerCase();
        String[] deepKeys = {"race", "racing", "drive", "driving", "shoot", "shooting", "gun", "gunfight",
                "sniper", "war", "tank", "fighter", "fighting", "combat", "strike", "fire", "3d",
                "moto", "car", "speed", "rush", "run", "runner", "sword", "blade", "hero", "legend",
                "world", "craft", "mine", "sim", "kart", "rpg", "ol", "mobile", "net",
                "kill", "dead", "zombie", "storm", "spy", "agent", "assassin"};
        String[] flatKeys = {"majiang", "mahjong", "chess", "card", "poker", "match", "puzzle", "tap",
                "fruit", "candy", "bird", "bubble", "farm", "cook", "cake", "dress", "girl",
                "beauty", "idol", "pet", "baby", "kids", "fish", "hunter",
                "samurai", "undead", "slayer", "ninja"};   // 固定视角动作（武士2/亡灵杀手等）
        for (String k : deepKeys) {
            if (s.contains(k)) return "提示：像3D场景游戏，推荐「深度场景型」（已默认选中）";
        }
        for (String k : flatKeys) {
            if (s.contains(k)) return "提示：像固定视角/2D游戏，推荐「平面分层型」";
        }
        return "提示：先试「深度场景型」（多数游戏适用），效果不佳再换「平面分层型」";
    }

    /** 构建模板库 = 内置3 + 官方119 */
    private String[][] buildLib() {
        // 官方模板：推荐模板（带描述）排最前，其余保持原名
        java.util.List<String[]> rec = new java.util.ArrayList<String[]>();
        java.util.List<String[]> rest = new java.util.ArrayList<String[]>();
        for (String[] t : REF_OFFICIAL) {
            String name = t[1];
            // 去掉"深度·/平面·"分类前缀（分类不准确，实测与游戏类型无关）
            if (name != null && (name.startsWith("深度·") || name.startsWith("平面·"))) {
                name = name.substring(3);
            }
            if (DEFAULT_TMPL.equals(t[2])) {
                // 唯一推荐：水果忍者（多数游戏效果最佳）
                rec.add(new String[]{t[0], "推荐·" + name, t[2]});
            } else {
                rest.add(new String[]{t[0], name, t[2]});
            }
        }
        String[][] lib = new String[rec.size() + rest.size()][];
        int i = 0;
        for (String[] t : rec) lib[i++] = t;
        for (String[] t : rest) lib[i++] = t;
        return lib;
    }

    /** 读取系统属性 */
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

    /** 设置 3D 深度：无 root，直接 setprop（本设备已实测普通权限可行） */
    private void setDepth(int d) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"setprop", "persist.sys.3deffect", String.valueOf(d)});
            p.waitFor();
        } catch (Exception e) { }
    }

    /** 写入配置：普通权限写 /storage/emulated/0/.gles.cfg（无需root） */
    private boolean writeCfg(String pkg, byte[] raw) {
        String newLine = pkg + "  " + Base64.encodeToString(raw, Base64.NO_WRAP);
        try {
            StringBuilder sb = new StringBuilder();
            File f = new File(CFG_DIR, CFG_NAME);
            boolean updated = false;
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.length() > 0 && !t.startsWith("#")) {
                        String[] parts = t.split("\\s+");
                        if (parts.length >= 1 && parts[0].equals(pkg)) {
                            sb.append(newLine).append("\n");
                            updated = true;
                            continue;
                        }
                    }
                    sb.append(line).append("\n");
                }
                br.close();
            } else {
                sb.append("#package_name   enableMix,eye_angle,focus_plane_pos,near_plane_pos,left_right_exchange\n");
            }
            if (!updated) sb.append(newLine).append("\n");

            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write(sb.toString());
            osw.flush();
            osw.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "写入异常", e);
            // 写入失败：询问用户是否重置（不自动重置，避免误删配置）
            confirmResetGles();
            return false;
        }
    }

    /** 写入失败时询问是否重置 .gles.cfg（用户确认后才重置，不自动执行） */
    private void confirmResetGles() {
        try {
            new AlertDialog.Builder(this, R.style.AppDialog)
                    .setTitle("写入配置失败")
                    .setMessage(".gles.cfg 可能已损坏（无法写入）。\n是否重置配置文件？\n\n重置会清除所有3D游戏配置，需要重新套模板。")
                    .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            // 用户确认：删除损坏文件，重建空配置
                            try {
                                File f = new File(CFG_DIR, CFG_NAME);
                                if (f.exists()) f.delete();
                                FileOutputStream fos = new FileOutputStream(f);
                                fos.write(("#package_name   enableMix,eye_angle,focus_plane_pos,near_plane_pos,left_right_exchange\n")
                                        .getBytes("UTF-8"));
                                fos.close();
                                loadCfg();
                                adapter.notifyDataSetChanged();
                                Toast.makeText(MainActivity.this, "已重置，请重新配置3D游戏",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Log.e(TAG, "重置失败", e);
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) { }
    }

    private boolean removeCfg(String pkg) {
        try {
            File f = new File(CFG_DIR, CFG_NAME);
            if (!f.exists()) return false;
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.length() > 0 && !t.startsWith("#")) {
                    String[] parts = t.split("\\s+");
                    if (parts.length >= 1 && parts[0].equals(pkg)) continue;
                }
                sb.append(line).append("\n");
            }
            br.close();
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write(sb.toString());
            osw.flush();
            osw.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "删除异常", e);
            return false;
        }
    }

    class AppAdapter extends BaseAdapter {
        Context ctx;
        AppAdapter(Context c) { ctx = c; }
        @Override public int getCount() { return appList.size(); }
        @Override public Object getItem(int i) { return appList.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(ctx).inflate(R.layout.item_app, null);
            }
            AppInfo info = appList.get(pos);
            ImageView iv = (ImageView) convertView.findViewById(R.id.iv_icon);
            TextView tvLabel = (TextView) convertView.findViewById(R.id.tv_label);
            TextView tvPkg = (TextView) convertView.findViewById(R.id.tv_pkg);
            TextView tvTag = (TextView) convertView.findViewById(R.id.tv_tag);
            if (info.icon != null) {
                iv.setImageDrawable(info.icon);
            } else {
                if (fallbackIcon == null) {
                    fallbackIcon = getResources().getDrawable(android.R.drawable.sym_def_app_icon);
                }
                iv.setImageDrawable(fallbackIcon);
            }
            tvLabel.setText(info.label);
            tvPkg.setText(info.pkg);
            // [3D] 标识分色（Material 徽章）：3D游戏=绿，3D应用=橙（类型单选不共存）
            if (info.hasCfg) {
                tvTag.setText("[3D]");
                tvTag.setTextColor(0xFFFFFFFF);
                tvTag.setBackgroundResource(R.drawable.badge_green);
            } else if (info.hasApp3d) {
                tvTag.setText("[3D]");
                tvTag.setTextColor(0xFFFFFFFF);
                tvTag.setBackgroundResource(R.drawable.badge_orange);
            } else {
                tvTag.setText("");
                tvTag.setBackgroundResource(0);
            }
            return convertView;
        }
    }

    // ===== 官方 119 条参考库（按类型分组: 深度·/平面·）=====
    private static final String[][] REF_OFFICIAL = {
{"com.zhangqu.game.tank3D.wdj", "深度·3D坦克争霸", "S0RYHDNS+voB/woERRRkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.vectorunit.purple.skymobi", "深度·4D极速沙滩赛车", "S0RYHCJ9bEEB/woEVEJkAAAAAAAAAAAAAAAAABQAAAA="},
        {"com.netease.raven.huawei", "深度·Raven：掠夺者", "S0RYHGYn78AB/w8CRBxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.InfinityGames.Uni", "深度·Uni格斗", "S0RYHMmzoOoB/woERjNkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.linekong.sszj.HUAWEI", "深度·三国战纪", "S0RYHPAebMYB/woNQxBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.kuyou.sdbgj.baidu", "深度·三打白骨精", "S0RYHHge4ZQB/woHTh5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.netease.ma.ewan", "深度·乖离性百万亚瑟王", "S0RYHGPaCt0B/woDQhRkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.snailgame.jyyd.baidu", "深度·九阴真经(百度)", "S0RYHNIzpcEB/woHUxRkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.snailgame.jyzjyd.sy37", "深度·九阴真经3D", "S0RYHNIbFGwB/woEQRBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.ourpalm.ldtj.baidu", "深度·乱斗天骄", "S0RYHF/fOWoB/woaUChkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.skogame.yzg.baidu", "深度·云中歌", "S0RYHNIFJ98B/w4JTSNkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.ninefang.xgz.dk", "深度·仙国志", "S0RYHF9/+kEB/woEUihkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.netease.l10.baidu", "深度·倩女幽魂", "S0RYHCeexu8B/wgA6/RkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.feelingtouch.sniperzombie", "深度·僵尸前线3D", "S0RYHGP3/8EB/xQLTChkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.blcm.gzlc.huawei", "深度·光明大陆", "S0RYHDNS+u8B/w0BNQ5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.crisisfire.android.wdj", "深度·全民枪战", "S0RYHGzJ3bYB/w0HUDxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.WeFire", "深度·全民突击", "S0RYHNIt+8UB/wwCThFkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.zlongame.qqh.qihu", "深度·全球行动", "S0RYHBtJ654B/woITBZkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.ttcz", "深度·六龙争霸", "S0RYHCee+6YB/woFRRxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"sh.lilith.dgame.DK", "深度·刀塔传奇", "S0RYHMlJ2PQB/wgATxBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.Avalon.amazingrun", "深度·勇敢向前冲", "S0RYHBuA3XUB/x0BWRBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.happyfishjoy", "深度·华人捕鱼", "S0RYHHgeAG4B/wgATSBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.zplay.manuganu2", "深度·印第安大冒险2", "S0RYHNInJ98B/w0KRh9kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.Alper.Manuganu", "深度·印第安探险", "S0RYHPAezMYB/wwBTAxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.zh.fs", "深度·反恐使命3D", "S0RYHAVSbDYB/xQFRCJkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.pocketmon.cw.baidu", "深度·口袋妖怪复刻版", "S0RYHETwoCcB/woHWB5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.huorong.papasg.wdj", "深度·啪啪三国", "S0RYHHfa76YB/woHPBlkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.netease.dhxy.baidu", "深度·大话西游", "S0RYHNdJUMYB/w4KVShkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.game.VXDGame", "深度·天天炫斗", "S0RYHNItzAAB/wkHGQ5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.game.SSGame", "深度·天天飞车", "S0RYHCfa4OsB/w0QV0RkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.cyou.cx.mtlbb.baidu", "深度·天龙八部3D(百度)", "S0RYHNLXxiIB/woFRCJkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.feamber.spaceracing.tecent", "深度·太空飞车", "S0RYHFXfKE4B/woEVFFkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.dedalord.runningfred", "深度·奔跑的弗雷德", "S0RYHAWRvuAB/xAEUTNkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.dweb.ultramanrumble", "深度·奥特曼大乱斗", "S0RYHMmnu4UB/wkBQxBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.shediao.cnyxw", "深度·射雕侠客传", "S0RYHC1ww4UB/w4YUjxkAAAAAAAAAAAAAAAAAAAUAAA="},
        {"com.miHoYo.HSoDv2.baidu", "深度·崩坏学园2", "S0RYHMlDFPAB/woK9fdkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.yinhan.huancheng.baidu", "深度·幻城", "S0RYHDPfPE4B/wsIVRBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.zlongame.yjqy.baidu", "深度·御剑情缘", "S0RYHBtJ654B/woITBZkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.niel.angrybots", "深度·愤怒机器人", "S0RYHGzJ3bYB/w0HUDxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.rovio.baba.kunlun", "深度·愤怒的小鸟2", "S0RYHAWb/3sB/w8AVTNkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.qihoo.wm", "深度·我叫MT 外传", "S0RYHETwFGMB/woFRhJkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.mt2", "深度·我叫MT2", "S0RYHEG85X4B/woB9QBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.immt3", "深度·我叫MT3", "S0RYHBtSw28B/wUGVTdkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.elextech.alert", "深度·战警：大国崛起", "S0RYHDNS/2AB/woHTiJkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.ledo.kof97ol.huawei", "深度·拳皇97 OL", "S0RYHNdJpWQB/woDVENkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.smbls2", "深度·数码暴龙兽3D", "S0RYHNLD/2EB/wgPCgBkAAAAAAAAAAAAAAAAAAAUAAA="},
        {"com.tencent.tmgp.xfsn2", "深度·旋风少女", "S0RYHCeFG10B/w0FQSBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.wsxsms", "深度·无双小师妹", "S0RYHPBf60kB/wsAThZkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.ahzs.DK", "深度·暗黑战神", "S0RYHC2i4ZQB/wkDVjhkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"mobi.shoumeng.hjyc3d", "深度·梦幻西游", "S0RYHNItpTsB/w4AVARkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.netease.my.baidu", "深度·梦幻西游(百度)", "S0RYHMn93VcB/woASxVkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.qqgame.happymj", "深度·欢乐麻将全集", "S0RYHFVJ/yUB/wUAMzNkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.firevale.dhjhcmge", "深度·武侠Q传", "S0RYHDmApT4B/woEURhkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.changyuan.hundun.baidu", "深度·混沌传说", "S0RYHHfaPFoB/wgGWSZkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.DBGame.DiabloLOL.wdj", "深度·火柴人联盟", "S0RYHMn96voB/woHVEtkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.glu.flcn_new", "深度·火线指令诺曼底", "S0RYHAVwUN0B/woDTx1kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.ttxw91.ttxw", "深度·炫舞天团", "S0RYHETwFGMB/woFRhJkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.act.hot1.baidu", "深度·热血街霸", "S0RYHMmtpcsB/w4JLxlkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.killer.baidu", "深度·独立防线", "S0RYHGP3Y4AB/wwCTh5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.dfzj", "深度·盗墓笔记", "S0RYHF96J10B/woAPhBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.sxddc", "深度·神仙道", "S0RYHMw5pUMB/woHQR5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.skymobi.majormayhem", "深度·致命伤害", "S0RYHHhfJ1IB/woCVCNkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.fl.mgsygh.baidu", "深度·舞剑者们", "S0RYHHee0loB/woESRhkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.linekong.t3.wdj", "深度·苍穹之剑", "S0RYHC2iIv8B/woHVEFkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.zqgame.leagueofmasters.qihoo360", "深度·英魂之刃", "S0RYHCjJ/4AB/w0BSydkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.qingluangame.mengniang.v5.uc", "深度·萌娘契约", "S0RYHCdJ9OQB/woDSwtkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.wh.xyfm.baidu", "深度·西游降魔篇3D", "S0RYHC2tFB4B/wkHVShkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.qdazzle.zxjwm.baidu", "深度·诛仙诀", "S0RYHNdJPL4B/woDVSJkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.lxd.baimeng.seer.qihu", "深度·赛尔号之烈火苍穹", "S0RYHGZ95AAB/w0NSwtkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.miyi.seer", "深度·赛尔号王者归来", "S0RYHH2eu2sB/w0HUkhkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.joym.Warrior", "深度·铠甲勇士", "S0RYHGPa3ewB/xAHWC1kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.gbits.atm.baidu", "深度·问道", "S0RYHDPfPE4B/wsIVRBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.netease.onmyoji.baidu", "深度·阴阳师", "S0RYHNIzpcEB/woHUxRkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.oyatsukai.fractalcombat", "深度·霹雳空战", "S0RYHF9JIvsB/w8FTzhkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.amazing.magicage.baidu", "深度·魔力时代", "S0RYHNJm5O8B/woARgtkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.u9time.mt.baidu", "深度·魔塔之英雄无敌", "S0RYHC2tFB4B/wkHVShkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"a.com.modo.game.dragonisland", "深度·魔龙岛", "S0RYHNLS4PsB/wsHWChkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.kongzhong.s9.baidu", "深度·魔龙战记", "S0RYHF/f8cUB/woDViNkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.feitan.waitan", "平面·3D外滩", "S0RYHF9XWrsB/2QEYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.xiaoao.moto3d2.wdj", "平面·3D摩托飞车2", "S0RYHHhf9X8B/2QEX19kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.feitan.gugong", "平面·3D故宫", "S0RYHF9XWrsB/2QEYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.newcreate.haochemain", "平面·3D极品赛车", "S0RYHHhf/vAB/2QAX19kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.feitan.milan", "平面·3D米兰", "S0RYHF9XWrsB/2QEYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.noblemuffins.Grudger", "平面·乌托邦跑酷", "S0RYHDNS8VYB/2QCX19kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.snailgame.panda.qihoo360", "平面·九阴真经(360)", "S0RYHChjQTsB/2QFYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.netease.ldxy.baidu", "平面·乱斗西游2", "S0RYHAVwFP8B/2QAXl5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.bear.UnLuckBear.wdj", "平面·倒霉熊奇幻3D冒险", "S0RYHDNS734B/2QHX19kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.joym.backkomskiing.wandoujia", "平面·倒霉熊极速狂飙", "S0RYHF9XwyUB/2QAWlpkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.tanke", "平面·全民打坦克", "S0RYHBv9CqoB/2QBXl5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.cmstrike", "平面·全民生化", "S0RYHF/E0uEB/2QKYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.catmoonproductions.trucksim.pickup.dest", "平面·农场障碍赛车", "S0RYHAVwX10B/2QBYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.jbgames.HTGame.huawei", "平面·口袋训练师", "S0RYHHeeAFkB/18AYWFkAAAAAAAAAAAAAAAAABQAAAA="},
        {"cn.edu.nuc.tankwar", "平面·坦克战", "S0RYHAVS6r8B/2QDYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.dbm", "平面·大话西游手游", "S0RYHHeeBWoB/2QAYWFkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.joniy.ttkpcem", "平面·天天酷跑车", "S0RYHHhf/vAB/2QAX19kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.Alice", "平面·天天风之旅", "S0RYHMnfPOsB/2QFXl5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.tjxm2sq", "平面·天龙八部3D", "S0RYHHhf/vAB/2QAX19kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.snailgame.pandabaidu", "平面·太极熊猫", "S0RYHChjQTsB/2QFYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.snailgame.pandatwo.baidu", "平面·太极熊猫2", "S0RYHHhf/vAB/2QAX19kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.youzu.kbzy.huawei", "平面·女神联盟2", "S0RYHH39HvEB/2QBYWFkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.wanmei.xom.dangle", "平面·完美世界手游", "S0RYHFVJ4XoB/2QEXFxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.mojang.minecraftpe", "平面·我的世界", "S0RYHHeeBWoB/2QAYWFkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.fengniao.qtdl.qihu", "平面·抢滩登陆3D", "S0RYHC2eGzsB/2QAYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.tencent.tmgp.cmge.xishanju.tggame", "平面·新射雕群侠传之铁血丹心", "S0RYHF968W4B/2EBXV1kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"cn.atme.ahxt.baidu", "平面·暗黑血统", "S0RYHMlJ4DcB/2QHXV1kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.ultralisk.guobao.none", "平面·果宝特攻:保卫采莲", "S0RYHDwi/+YB/2ECXFxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.joym.fruitkingunion.sj360", "平面·果宝特攻王者联盟", "S0RYHEHm4BUB/2QFVlZkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.halfbrick.fruitninja", "平面·水果忍者", "S0RYHHhaxusB/2QAV1dkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"cn.mobage.g12000142.huawei", "平面·热砂之乐园", "S0RYHC2eGzsB/2QAYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.joym.bearsadventure.yingyongbao", "平面·熊出没大冒险", "S0RYHER3UP8B/2QMXV1kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.yodo1.sniper3d.YODO1", "平面·狙击行动3D代号猎鹰", "S0RYHF/f/qQB/2QJXl5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.yodo1.sniper3d.huawei", "平面·狙击行动：代号猎鹰", "S0RYHF/f/qQB/2QJXl5kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.wanmei.xom.BD", "平面·笑傲江湖", "S0RYHFVJ4XoB/2QEXFxkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.transylgamia.ultimate3dclassiccarrally", "平面·终极老爷车接力赛", "S0RYHAVwX10B/2QBYGBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.kunlun.smsy.huawei", "平面·蜀门手游", "S0RYHH39HvEB/2QBYWFkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.Janlr.superwings", "平面·超级飞侠", "S0RYHMNJ6tkB/2QJXV1kAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.oyatsukai.fractalcombatxpremium", "平面·霹雳空战X", "S0RYHF+bfXEB/14aWlBkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.game.hypld.qihoo", "平面·黄易派来的", "S0RYHH1SoOoB/2QDYmJkAAAAAAAAAAAAAAAAAAAAAAA="},
        {"com.linekong.dbm.bd", "平面·黎明之光", "S0RYHHeeBWoB/2QAYWFkAAAAAAAAAAAAAAAAAAAAAAA="},
    };
}
