package com.dbos.pilot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.util.Log;

/**
 * Self-healing job. The ROM will not start third-party processes for
 * broadcasts, but JobSchedulerService is a system process and pulls the app
 * process itself to run onStartJob — the one self-start path that survives.
 * The job only ensures the resident service is alive (idempotent).
 */
public class KeepJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            Intent svc = new Intent(this, PilotService.class);
            startForegroundService(svc);
            Log.i(PilotService.TAG, "keep job " + params.getJobId() + " fired -> ensure service");
        } catch (Throwable t) {
            Log.e(PilotService.TAG, "keep job start failed", t);
        }
        // Re-arm the short-deadline one-shot chain: at any power-off moment
        // the system holds an already-due job that fires soon after boot.
        try {
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            if (js != null) PilotService.armBootTrigger(js);
        } catch (Throwable ignored) {}
        jobFinished(params, false);
        return false;   // work completed synchronously
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;    // interrupted: ask for a reschedule
    }
}
