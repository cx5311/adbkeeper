package com.cx531.adbkeep;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 周期兜底：每 15 分钟（系统允许的最小周期）检查一次，仅在联网时执行。
 *
 * 为什么要周期任务：某些 ROM 会在运行过程中把 adb_enabled 清零（例如待机/唤醒流程），
 * 而那时如果 Aerial 恰好崩了，就没人补。这个任务由系统调度、跨重启持久（setPersisted），
 * 执行时只读一个 int，开销可以忽略。
 */
public class KeepJobService extends JobService {

    private static final String TAG = "AdbKeeper";
    static final int JOB_ID = 1001;

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean changed = Keeper.ensure(this, "job");
        Log.i(TAG, "job done, changed=" + changed);
        jobFinished(params, false);
        return false;   // 已同步完成，不需要 wakelock 续命
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;    // 被系统中断则稍后重试
    }

    /** 幂等排期：已有同 ID 任务就不重复添加。 */
    public static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            Log.w(TAG, "no JobScheduler");
            return;
        }
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_ID) {
                return;
            }
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, KeepJobService.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        int r = js.schedule(job);
        Log.i(TAG, "job scheduled: " + (r == JobScheduler.RESULT_SUCCESS));
    }
}
