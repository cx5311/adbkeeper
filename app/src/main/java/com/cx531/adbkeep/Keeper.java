package com.cx531.adbkeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 唯一的功能实现：把 Settings.Global.ADB_ENABLED 保持在 1。
 *
 * 为什么只写这一个值就够（电视上）：
 *   - adb_enabled 是持久化的 Global 设置，写一次就一直有效，不需要每次开机都成功
 *   - 端口 5555 由 persist.adb.tcp.port 固定（本机已为 5555），与 adb_enabled 无关
 *   - adbd 运行在 system_server 侧，Aerial 崩溃 / 桌面黑屏都不影响它
 *
 * 与手机的差异（参考 Jocala/wifiadb 的结论）：
 *   手机 Android 11+ 用 adb_wifi_enabled + 随机 TLS 端口，写不了、只能靠无障碍点磁贴；
 *   电视走传统 adb_enabled + 固定 5555，WRITE_SECURE_SETTINGS 即可直接写，不需要无障碍。
 */
public final class Keeper {

    private static final String TAG = "AdbKeeper";
    private static final String PREFS = "keeper";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_RUN = "last_run";
    private static final String KEY_LAST_CHANGE = "last_change";

    private Keeper() {
    }

    /** 用一个在直启(解锁前)阶段也安全的存储上下文。 */
    static Context safe(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return c.createDeviceProtectedStorageContext();
            } catch (Throwable ignored) {
                // 落回原上下文
            }
        }
        return c;
    }

    static SharedPreferences prefs(Context c) {
        return safe(c).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 用户总开关（在状态页可切换）。关掉后本应用完全不动作。 */
    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 当前 adb_enabled 值；读不到返回 -1。 */
    public static int adbEnabled(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String lastRun(Context c) {
        return prefs(c).getString(KEY_LAST_RUN, "从未");
    }

    public static String lastChange(Context c) {
        return prefs(c).getString(KEY_LAST_CHANGE, "从未（一直开着）");
    }

    /**
     * 检查并修复。
     *
     * @param reason 触发来源，仅用于日志
     * @return true 表示这次确实写入了（从关→开）
     */
    public static boolean ensure(Context c, String reason) {
        if (!isEnabled(c)) {
            Log.i(TAG, reason + ": skipped (disabled by user)");
            return false;
        }
        if (!hasPermission(c)) {
            // 没有权限只能等，不要反复尝试写
            Log.w(TAG, reason + ": no WRITE_SECURE_SETTINGS, run: adb shell pm grant "
                    + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
            return false;
        }

        SharedPreferences p = prefs(c);
        p.edit().putString(KEY_LAST_RUN, now()).apply();

        if (adbEnabled(c) == 1) {
            return false;   // 已经是开的，什么都不做（避免无谓的设置写入）
        }

        try {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            p.edit().putString(KEY_LAST_CHANGE, now()).apply();
            Log.i(TAG, reason + ": adb_enabled was off -> turned back on");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": write failed", t);
            return false;
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
