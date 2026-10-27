package com.cx531.adbkeep;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 快速通道：每 2 分钟检查一次。
 *
 * 为什么需要它：JobScheduler 的周期下限是 15 分钟（Android 框架硬限制，API 24+ 传更小的值
 * 会直接抛 IllegalArgumentException），且 setPeriodic 默认 flex 等于周期，系统可以在
 * 15-30 分钟内任选时机执行。真机实测（MiTV-MFFU0 / Android 14）一次恢复用了 21 分钟，
 * 对"救砖"来说太慢。
 *
 * 这里用 AlarmManager 的 ELAPSED_REALTIME（不是 WAKEUP 类型）：
 *   - 设备醒着：每 2 分钟检查一次，最迟 2-3 分钟恢复
 *   - 设备待机：alarm 随 uptime 一起暂停，不会把电视从待机里叫醒去耗电；
 *     一旦被唤醒，过期的 alarm 会立刻补跑一次 —— 正好是"唤醒后马上恢复"的效果
 * 15 分钟的 Job 继续保留，作为跨重启持久化的最终兜底。
 */
public class FastAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AdbKeeper";
    private static final int REQ = 2001;
    static final long INTERVAL = 2 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        Keeper.ensure(context, "alarm");
    }

    /** 幂等：同一个 PendingIntent 重复 setInexactRepeating 只会覆盖，不会叠加。 */
    static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.w(TAG, "no AlarmManager");
            return;
        }
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pending(context));
            Log.i(TAG, "fast alarm scheduled (2min)");
        } catch (Throwable t) {
            Log.w(TAG, "alarm schedule failed", t);
        }
    }

    static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            am.cancel(pending(context));
        } catch (Throwable ignored) {
            // 取消失败无所谓
        }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, FastAlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, REQ, i, flags);
    }
}
