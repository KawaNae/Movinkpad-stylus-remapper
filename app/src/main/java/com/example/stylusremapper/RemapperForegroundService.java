package com.example.stylusremapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Display;

public class RemapperForegroundService extends Service {

    private static final String CHANNEL_ID = "stylus_remapper";
    private static final int NOTIFICATION_ID = 1;

    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    pushRotation();
                }
            }

            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}
        };
        displayManager.registerDisplayListener(displayListener, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("mappings", MODE_PRIVATE);
        String summary = prefs.getString("notification_summary", getString(R.string.notification_default_text));

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(summary)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        pushRotation();
        return START_STICKY;
    }

    private void pushRotation() {
        IRemapperService service = ShizukuHelper.getService();
        if (service == null) return;
        Display d = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return;
        try {
            android.graphics.Point size = new android.graphics.Point();
            d.getRealSize(size);
            service.setRotation(d.getRotation(), size.x, size.y);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        displayManager.unregisterDisplayListener(displayListener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
