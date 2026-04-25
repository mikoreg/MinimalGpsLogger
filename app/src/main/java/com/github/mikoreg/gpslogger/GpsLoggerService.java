package com.github.mikoreg.gpslogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;

import org.jspecify.annotations.Nullable;

public final class GpsLoggerService extends Service {
    public static final String ACTION_START = "com.github.mikoreg.gpslogger.action.START";
    public static final String ACTION_STOP = "com.github.mikoreg.gpslogger.action.STOP";
    public static final String EXTRA_DEVICE_ADDRESS = "com.github.mikoreg.gpslogger.extra.DEVICE_ADDRESS";
    public static final String EXTRA_DEVICE_NAME = "com.github.mikoreg.gpslogger.extra.DEVICE_NAME";

    private static final String CHANNEL_ID_LOW = "gps_logger_channel_low";
    private static final String CHANNEL_ID_HIGH = "gps_logger_channel_high";
    private static final int NOTIFICATION_ID = 42;
    private static final int ALERT_NOTIFICATION_ID = 43;

    private final LocalBinder binder = new LocalBinder();
    private volatile NmeaStats stats = NmeaStats.idle();
    private volatile @Nullable NmeaLoggerEngine engine;
    private volatile @Nullable Thread engineThread;

    public final class LocalBinder extends Binder {
        public GpsLoggerService service() {
            return GpsLoggerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
            String name = intent.getStringExtra(EXTRA_DEVICE_NAME);
            if (address == null || address.isEmpty()) {
                stats = NmeaStats.error("Missing Bluetooth MAC address.");
                return START_NOT_STICKY;
            }
            if (name == null || name.isEmpty()) {
                name = address;
            }
            startInForeground();
            startEngine(address, name);
            return START_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopEngineAndSelf();
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public @Nullable IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopEngineOnly();
        super.onDestroy();
    }

    public NmeaStats stats() {
        return stats;
    }

    private void startEngine(String address, String deviceName) {
        stopEngineOnly();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            stats = NmeaStats.error("No Bluetooth adapter found.");
            onCriticalError("Hardware error: No Bluetooth");
            stopForegroundCompat();
            stopSelf();
            return;
        }

        NmeaLoggerEngine newEngine = new NmeaLoggerEngine(
                this,
                adapter,
                address,
                deviceName,
                new NmeaLoggerEngine.StatsSink() {
                    @Override
                    public void onStats(NmeaStats newStats) {
                        stats = newStats;
                        updateForegroundNotification();
                    }

                    @Override
                    public void onCriticalError(String message) {
                        GpsLoggerService.this.onCriticalError(message);
                    }
                }
        );
        Thread newThread = new Thread(newEngine, "gps-nmea-logger");
        engine = newEngine;
        engineThread = newThread;
        newThread.start();
    }

    private void stopEngineAndSelf() {
        stats = stats.withState("STOPPING");
        stopEngineOnly();
        stats = NmeaStats.idle();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(ALERT_NOTIFICATION_ID);
        stopForegroundCompat();
        stopSelf();
    }

    private void stopEngineOnly() {
        NmeaLoggerEngine currentEngine = engine;
        if (currentEngine != null) {
            currentEngine.stop();
        }
        Thread currentThread = engineThread;
        if (currentThread != null && currentThread != Thread.currentThread()) {
            try {
                currentThread.join(1500L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        engine = null;
        engineThread = null;
    }

    private void startInForeground() {
        Notification notification = buildForegroundNotification("GPS Logger Running", "Initializing...", R.drawable.ic_stat_gps_waiting);
        if (Build.VERSION.SDK_INT >= 29) {
            // Using literal value 0x00000010 for FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE 
            // to avoid verification issues on Android 5
            startForeground(NOTIFICATION_ID, notification, 0x00000010);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateForegroundNotification() {
        String state = stats.state();
        boolean isLogging = "LOGGING".equals(state);
        int iconRes = isLogging ? R.drawable.ic_stat_gps_logging : R.drawable.ic_stat_gps_waiting;
        
        String contentText = String.format("Status: %s | Fix: %s", state, stats.fixValid() ? "YES" : "NO");
        Notification notification = buildForegroundNotification("GPS Logger Running", contentText, iconRes);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void onCriticalError(String message) {
        showHighPriorityNotification("CRITICAL LOGGER ERROR", message);
        triggerVibration();
    }

    private void triggerVibration() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(1000);
        }
    }

    private Notification buildForegroundNotification(String title, String text, int iconRes) {
        PendingIntent pendingIntent = createContentIntent();
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID_LOW)
                : new Notification.Builder(this);

        return builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(iconRes)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void showHighPriorityNotification(String title, String text) {
        PendingIntent pendingIntent = createContentIntent();
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID_HIGH)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH);

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound == null) alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        Notification notification = builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_gps_waiting)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setSound(alarmSound)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(ALERT_NOTIFICATION_ID, notification);
        }
    }

    private PendingIntent createContentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= 0x04000000; // PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel low = new NotificationChannel(CHANNEL_ID_LOW, "GPS Logger Status", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(low);

        NotificationChannel high = new NotificationChannel(CHANNEL_ID_HIGH, "GPS Logger Alerts", NotificationManager.IMPORTANCE_HIGH);
        high.enableLights(true);
        high.enableVibration(true);
        high.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound == null) alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        
        high.setSound(alarmSound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        
        manager.createNotificationChannel(high);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
