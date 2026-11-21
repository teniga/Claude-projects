package nl.screenshotopruimer;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Daily background job that runs the cleanup. */
public class CleanupJobService extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Cleaner.run(getApplicationContext());
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
