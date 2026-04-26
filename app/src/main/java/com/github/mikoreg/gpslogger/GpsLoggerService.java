package com.github.mikoreg.gpslogger;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;
import android.util.Log;

import org.jspecify.annotations.Nullable;

public final class GpsLoggerService extends Service {
    private static final String TAG = "GpsLoggerService";
    public static final String ACTION_START = "com.github.mikoreg.gpslogger.action.START";
    public static final String ACTION_STOP = "com.github.mikoreg.gpslogger.action.STOP";
    public static final String EXTRA_DEVICE_ADDRESS = "com.github.mikoreg.gpslogger.extra.DEVICE_ADDRESS";
    public static final String EXTRA_DEVICE_NAME = "com.github.mikoreg.gpslogger.extra.DEVICE_NAME";
    public static final String EXTRA_SOURCE_TYPE = "com.github.mikoreg.gpslogger.extra.SOURCE_TYPE";
    public static final String SOURCE_BLUETOOTH = "bluetooth";
    public static final String SOURCE_INTERNAL = "internal";
    public static final String SOURCE_USB = "usb";

    private static final String CHANNEL_ID_LOW = "gps_logger_channel_low";
    private static final String CHANNEL_ID_HIGH = "gps_logger_channel_high";
    private static final int NOTIFICATION_ID = 42;
    private static final int ALERT_NOTIFICATION_ID = 43;

    private final LocalBinder binder = new LocalBinder();
    private volatile NmeaStats stats = NmeaStats.idle();
    private volatile @Nullable StoppableLoggerEngine engine;
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
            String sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE);
            if (sourceType == null || sourceType.isEmpty()) {
                sourceType = SOURCE_BLUETOOTH;
            }
            String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
            String name = intent.getStringExtra(EXTRA_DEVICE_NAME);
            if (SOURCE_BLUETOOTH.equals(sourceType) && (address == null || address.isEmpty())) {
                stats = NmeaStats.error("Missing Bluetooth MAC address.");
                return START_NOT_STICKY;
            }
            if (SOURCE_USB.equals(sourceType) && (address == null || address.isEmpty())) {
                stats = NmeaStats.error("Missing USB device name.");
                return START_NOT_STICKY;
            }
            if (SOURCE_INTERNAL.equals(sourceType) && !hasFineLocationPermission()) {
                stats = NmeaStats.error("Missing ACCESS_FINE_LOCATION permission.");
                onCriticalError("Internal GPS permission missing.");
                return START_NOT_STICKY;
            }
            if (name == null || name.isEmpty()) {
                name = address;
            }
            startInForeground(sourceType);
            startEngine(sourceType, address, name);
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

    private boolean hasFineLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startEngine(String sourceType, @Nullable String address, @Nullable String deviceName) {
        stopEngineOnly();

        NmeaLoggerEngine.StatsSink statsSink = new NmeaLoggerEngine.StatsSink() {
            @Override
            public void onStats(NmeaStats newStats) {
                stats = newStats;
                updateForegroundNotification();
            }

            @Override
            public void onCriticalError(String message) {
                GpsLoggerService.this.onCriticalError(message);
            }
        };

        StoppableLoggerEngine newEngine;
        if (SOURCE_INTERNAL.equals(sourceType)) {
            newEngine = new InternalNmeaEngine(this, statsSink);
        } else if (SOURCE_USB.equals(sourceType)) {
            if (address == null || address.isEmpty()) {
                stats = NmeaStats.error("Missing USB device name.");
                stopForegroundCompat();
                stopSelf();
                return;
            }
            String safeDeviceName = deviceName == null || deviceName.isEmpty() ? address : deviceName;
            newEngine = new UsbNmeaEngine(this, address, safeDeviceName, statsSink);
        } else {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                stats = NmeaStats.error("No Bluetooth adapter found.");
                onCriticalError("Hardware error: No Bluetooth");
                stopForegroundCompat();
                stopSelf();
                return;
            }
            if (address == null || address.isEmpty()) {
                stats = NmeaStats.error("Missing Bluetooth MAC address.");
                stopForegroundCompat();
                stopSelf();
                return;
            }
            String safeDeviceName = deviceName == null || deviceName.isEmpty() ? address : deviceName;
            newEngine = new NmeaLoggerEngine(this, adapter, address, safeDeviceName, statsSink);
        }

        Thread newThread = new Thread(newEngine, "gps-nmea-logger-" + sourceType);
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
        StoppableLoggerEngine currentEngine = engine;
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

    private void startInForeground(String sourceType) {
        Notification notification = buildForegroundNotification("GPS Logger Running", "Initializing...", R.drawable.ic_stat_gps_waiting);
        if (Build.VERSION.SDK_INT >= 29) {
            int foregroundServiceType = SOURCE_INTERNAL.equals(sourceType)
                    ? 0x00000008  // ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    : 0x00000010; // ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
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
        Log.e(TAG, "CRITICAL LOGGER ERROR: " + message);
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
                .setColor(0xFF1565C0)
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
                .setColor(0xFFC62828)
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
