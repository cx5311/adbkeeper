package com.cx531.adbkeep;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 唯一的功能实现：把 Settings.Global.ADB_ENABLED 保持在 1，并确认 adbd 真的在监听。
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
    private static final String KEY_MISS = "miss";

    /** 连续这么多次遇到"值是 1 但端口不通"才动手重启 adbd，避免偶发抖动误伤正常连接。 */
    private static final int MISS_THRESHOLD = 2;
    private static final int PROBE_TIMEOUT_MS = 400;
    private static final int DEFAULT_PORT = 5555;

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
        // 用 PackageManager.checkPermission 而不是 Context.checkSelfPermission：
        // 后者是 API 23 才加入的，minSdk 21 的设备上会 NoSuchMethodError。
        return c.getPackageManager().checkPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS, c.getPackageName())
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

    /** adbd 的 TCP 端口：读 persist.adb.tcp.port，读不到退回 5555。 */
    public static int adbPort() {
        String v = sysProp("persist.adb.tcp.port");
        if (v != null) {
            try {
                int p = Integer.parseInt(v.trim());
                if (p > 0 && p < 65536) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
                // 退回默认端口
            }
        }
        return DEFAULT_PORT;
    }

    /**
     * 设备是否真的配了 ADB TCP 端口。
     * 明确读到空/0/-1 说明只在 USB 上监听，那就不该去重启 adbd；
     * 读不到（反射被限制）时按"本项目的前提：本来就是网络 ADB"处理，返回 true。
     */
    public static boolean tcpPortConfigured() {
        String v = sysProp("persist.adb.tcp.port");
        if (v == null) {
            return true;
        }
        String s = v.trim();
        return !s.isEmpty() && !"0".equals(s) && !"-1".equals(s);
    }

    /** 读系统属性。SystemProperties 是隐藏 API，反射失败就当读不到。 */
    private static String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class, String.class).invoke(null, key, "");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 探测 adbd 是否在本地 TCP 端口上监听。
     * 只连 127.0.0.1，不发任何数据、不访问外网；超时 400ms。
     */
    public static boolean adbdListening() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", adbPort()), PROBE_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                    // 关闭失败无所谓
                }
            }
        }
    }

    /**
     * 检查并修复。
     *
     * @param reason 触发来源，仅用于日志
     * @return true 表示这次确实动了设置（关→开，或重启了 adbd）
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

        if (adbEnabled(c) != 1) {
            return writeOn(c, p, reason);
        }

        // 值已经是 1：再确认 adbd 真的在听。某些 ROM 会杀掉 adbd 却保留设置值，
        // 那种情况下只看设置会一直以为"一切正常"，永远不动作。
        if (!tcpPortConfigured()) {
            return false;   // 设备只在 USB 上开 adb，不干预
        }
        if (adbdListening()) {
            if (p.getInt(KEY_MISS, 0) != 0) {
                p.edit().putInt(KEY_MISS, 0).apply();
            }
            return false;
        }

        int miss = p.getInt(KEY_MISS, 0) + 1;
        p.edit().putInt(KEY_MISS, miss).apply();
        Log.w(TAG, reason + ": adb_enabled=1 but 127.0.0.1:" + adbPort()
                + " closed (" + miss + "/" + MISS_THRESHOLD + ")");
        if (miss >= MISS_THRESHOLD) {
            p.edit().putInt(KEY_MISS, 0).apply();
            return kickAdbd(c);
        }
        return false;
    }

    /** 关 → 开。写之前顺带确保"开发者选项"总开关是开的。 */
    private static boolean writeOn(Context c, SharedPreferences p, String reason) {
        ContentResolver cr = c.getContentResolver();
        try {
            // 部分 ROM 在开发者选项总开关关闭时会忽略 adb_enabled，一并打开
            try {
                if (Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1) != 1) {
                    Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
                }
            } catch (Throwable ignored) {
                // 读/写不了就算了，adb_enabled 本身还是会写
            }
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
            p.edit().putString(KEY_LAST_CHANGE, now()).putInt(KEY_MISS, 0).apply();
            Log.i(TAG, reason + ": adb_enabled was off -> turned back on");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": write failed", t);
            return false;
        }
    }

    /**
     * 强制重启 adbd：0 → 1 翻转一次。
     *
     * adb_enabled 由 system_server 监听，写 0 停 adbd、写 1 拉起 adbd，
     * 所以在"值是 1 但进程没了"的情况下，翻转是唯一的自救手段。
     * 兜底：无论中途发生什么，最后都会把值写回 1，不会把 ADB 留在关闭状态。
     */
    public static boolean kickAdbd(Context c) {
        ContentResolver cr = c.getContentResolver();
        try {
            Log.w(TAG, "restarting adbd (0 -> 1)");
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0);
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
            prefs(c).edit().putString(KEY_LAST_CHANGE, now()).apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "restart failed, restoring adb_enabled=1", t);
            try {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
            } catch (Throwable ignored) {
                // 已经尽力了
            }
            return false;
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
