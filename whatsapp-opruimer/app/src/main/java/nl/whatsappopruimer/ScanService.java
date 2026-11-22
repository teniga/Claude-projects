package nl.whatsappopruimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Voorgrondservice zodat de scan doorloopt als het scherm uitgaat of je de app verlaat. */
public class ScanService extends Service implements ScanManager.Listener {

    static final String EXTRA_TREE = "tree";
    static final String EXTRA_SUBFOLDERS = "subfolders";
    static final String EXTRA_SKIP_DAYS = "skipDays";
    private static final String CHANNEL = "scan";
    private static final int NOTIF_ID = 1;

    private PowerManager.WakeLock wakeLock;
    private long lastNotify;

    static void start(Context c, Uri tree, boolean subfolders, int skipDays) {
        Intent i = new Intent(c, ScanService.class)
                .putExtra(EXTRA_TREE, tree.toString())
                .putExtra(EXTRA_SUBFOLDERS, subfolders)
                .putExtra(EXTRA_SKIP_DAYS, skipDays);
        c.startForegroundService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Scannen", NotificationManager.IMPORTANCE_LOW));
        Notification n = buildNotification("Scannen gestart…", 0, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (intent == null || ScanManager.running) return START_NOT_STICKY;
        Uri tree = Uri.parse(intent.getStringExtra(EXTRA_TREE));
        boolean sub = intent.getBooleanExtra(EXTRA_SUBFOLDERS, false);
        int days = intent.getIntExtra(EXTRA_SKIP_DAYS, 7);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opruimer:scan");
        wakeLock.acquire(6 * 60 * 60 * 1000L);

        ScanManager.running = true;
        ScanManager.addListener(this);
        new Thread(() -> {
            ScanManager.run(this, tree, sub, days);
            if (wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_DETACH);
            nm.notify(NOTIF_ID, buildNotification(ScanManager.status, 100, false));
            stopSelf();
        }, "scan-main").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onScanUpdate() {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 2000 || !ScanManager.running) return;
        lastNotify = now;
        getSystemService(NotificationManager.class)
                .notify(NOTIF_ID, buildNotification(ScanManager.status, ScanManager.progressPercent(), true));
    }

    private Notification buildNotification(String text, int percent, boolean ongoing) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        String firstLine = text == null ? "" : text.split("\n")[0];
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("WhatsApp Opruimer")
                .setContentText(firstLine)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) b.setProgress(100, percent, percent == 0);
        return b.build();
    }

    @Override
    public void onDestroy() {
        ScanManager.removeListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
