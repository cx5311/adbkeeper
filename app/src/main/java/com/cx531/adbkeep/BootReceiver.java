package com.cx531.adbkeep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机触发点。
 *
 * action 的分工：
 *   LOCKED_BOOT_COMPLETED —— 系统起来、用户还没解锁时就收到（最早的应用级时机，API 24+）
 *   BOOT_COMPLETED        —— 解锁之后的常规开机广播（兜底）
 *   MY_PACKAGE_REPLACED   —— 覆盖安装/升级之后立刻跑一次
 *   com.cx531.adbkeep.PING —— 手动激活（见 README：Android 8+ 上必须显式指定组件，
 *                             且要带 --include-stopped-packages，主推用 am start 打开状态页）
 *
 * 收到后：追加一次检查 + 重新排周期任务（JobScheduler 的持久任务理论上会自己恢复，
 * 但重排一次成本极低，能覆盖被系统清掉的情况）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AdbKeeper";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "?" : intent.getAction();
        Log.i(TAG, "boot trigger: " + action);

        Keeper.ensure(context, "boot(" + action + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            KeepJobService.schedule(context);
        }
    }
}
