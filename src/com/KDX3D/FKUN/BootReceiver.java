package com.KDX3D.FKUN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 开机自启：悬浮条开关开着时，开机后自动恢复悬浮条
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        android.util.Log.i("BootReceiver", "收到广播: " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // 检查悬浮条开关状态：开启才自启，关闭不自启
            SharedPreferences sp = context.getSharedPreferences("floatbar",
                    Context.MODE_PRIVATE);
            boolean enabled = sp.getBoolean("enabled", false);
            android.util.Log.i("BootReceiver", "悬浮条开关状态: " + enabled);
            if (enabled) {
                context.startService(new Intent(context, FloatBarService.class));
                android.util.Log.i("BootReceiver", "已启动 FloatBarService");
            }
        }
    }
}
