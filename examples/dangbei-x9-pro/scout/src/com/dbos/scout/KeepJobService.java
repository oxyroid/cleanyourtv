package com.dbos.scout;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.util.Log;

/**
 * v6 自愈层：JobScheduler 每 15 分钟调一次这个 Job。
 * ROM 拦 BOOT_COMPLETED 也没用——JobSchedulerService 是系统进程，
 * 它会直接把 App 进程拉起来跑 onStartJob，这条路径不走广播管控。
 * Job 里唯一要做的就是确保常驻服务活着（幂等）。
 */
public class KeepJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            Intent svc = new Intent(this, ScoutService.class);
            startForegroundService(svc);
            Log.i(ScoutService.TAG, "keep job " + params.getJobId() + " fired -> ensure service");
        } catch (Throwable t) {
            Log.e(ScoutService.TAG, "keep job start failed", t);
        }
        // 自续命：重新排一个短 deadline 的持久一次性 Job（778 链）。
        // 这样任何时刻关机，手里都攥着一个 deadline 已过的持久任务 → 开机即触发。
        try {
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            if (js != null) ScoutService.armBootTrigger(js);
        } catch (Throwable ignored) {}
        jobFinished(params, false);
        return false; // 工作已同步完成
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 被系统打断就要求重排
    }
}
