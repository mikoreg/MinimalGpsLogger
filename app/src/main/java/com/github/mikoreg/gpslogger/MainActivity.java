package com.github.mikoreg.gpslogger;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;
    private static final int REQUEST_POST_NOTIFICATIONS = 1002;
    private static final int REQUEST_STORAGE = 1003;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private @Nullable GpsLoggerService boundService;
    private @Nullable Spinner deviceSpinner;
    private @Nullable ArrayAdapter<DeviceListItem> deviceAdapter;
    private @Nullable TextView permissionText;

    private @Nullable TextView statusRow;
    private @Nullable TextView posRow;
    private @Nullable TextView fileRow;
    private @Nullable TextView detailRow;
    private @Nullable TextView debugRow;

    private @Nullable Button startBtn;
    private @Nullable Button stopBtn;
    private @Nullable Button exportGpxBtn;
    private @Nullable Button openMapBtn;

    private String lastLogFile = "";

    private final Runnable uiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatsUi();
            handler.postDelayed(this, 500L);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            GpsLoggerService.LocalBinder binder = (GpsLoggerService.LocalBinder) service;
            boundService = binder.service();
            refreshStatsUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        requestRuntimePermissionsIfNeeded();
        refreshBondedDevices();
    }

    private void initViews() {
        statusRow = findViewById(R.id.statusRow);
        posRow = findViewById(R.id.posRow);
        fileRow = findViewById(R.id.fileRow);
        detailRow = findViewById(R.id.detailRow);
        debugRow = findViewById(R.id.debugRow);

        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        exportGpxBtn = findViewById(R.id.exportGpxBtn);
        openMapBtn = findViewById(R.id.openMapBtn);

        deviceSpinner = findViewById(R.id.deviceSpinner);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (deviceSpinner != null) deviceSpinner.setAdapter(deviceAdapter);

        findViewById(R.id.refreshBtn).setOnClickListener(v -> refreshBondedDevices());
        findViewById(R.id.btSettingsBtn).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));

        if (startBtn != null) startBtn.setOnClickListener(v -> startLogging());
        if (stopBtn != null) stopBtn.setOnClickListener(v -> stopLogging());
        if (exportGpxBtn != null) exportGpxBtn.setOnClickListener(v -> exportToGpxWithNotification());
        
        permissionText = findViewById(R.id.permissionText);
        
        updateStatsUi(NmeaStats.idle());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, GpsLoggerService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        handler.post(uiRefreshRunnable);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(uiRefreshRunnable);
        if (boundService != null) {
            unbindService(serviceConnection);
            boundService = null;
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshBondedDevices();
        refreshPermissionText();
    }

    private void requestRuntimePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            }
        }
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissionText() {
        if (permissionText == null) return;
        if (hasBluetoothConnectPermission()) {
            permissionText.setText("BT Permission: OK");
            permissionText.setTextColor(0xFF888888);
        } else {
            permissionText.setText("Missing BLUETOOTH_CONNECT permission!");
            permissionText.setTextColor(0xFFFF0000);
        }
    }

    private void refreshBondedDevices() {
        if (deviceAdapter == null) return;
        deviceAdapter.clear();
        if (!hasBluetoothConnectPermission()) {
            refreshPermissionText();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            toast("No Bluetooth adapter.");
            return;
        }
        if (!adapter.isEnabled()) toast("Bluetooth is OFF.");
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice device : bonded) {
                String name = device.getName();
                deviceAdapter.add(new DeviceListItem(name == null ? "Unknown" : name, device.getAddress()));
            }
            deviceAdapter.notifyDataSetChanged();
        } catch (SecurityException ex) {
            toast("SecurityException accessing devices.");
        }
    }

    private void startLogging() {
        if (!hasBluetoothConnectPermission()) {
            requestRuntimePermissionsIfNeeded();
            return;
        }
        if (deviceSpinner == null || deviceSpinner.getSelectedItem() == null) {
            toast("Select a GPS device first.");
            return;
        }
        DeviceListItem item = (DeviceListItem) deviceSpinner.getSelectedItem();
        Intent intent = new Intent(this, GpsLoggerService.class);
        intent.setAction(GpsLoggerService.ACTION_START);
        intent.putExtra(GpsLoggerService.EXTRA_DEVICE_ADDRESS, item.address());
        intent.putExtra(GpsLoggerService.EXTRA_DEVICE_NAME, item.name());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopLogging() {
        Intent intent = new Intent(this, GpsLoggerService.class);
        intent.setAction(GpsLoggerService.ACTION_STOP);
        startService(intent);
    }

    private void refreshStatsUi() {
        GpsLoggerService service = boundService;
        NmeaStats stats = service == null ? NmeaStats.idle() : service.stats();
        updateStatsUi(stats);
    }

    private void updateStatsUi(NmeaStats stats) {
        if (statusRow == null || posRow == null || fileRow == null || detailRow == null || debugRow == null) return;

        String state = stats.state();
        boolean isWorking = !"IDLE".equals(state) && !"STOPPED".equals(state);

        if (startBtn != null) {
            startBtn.setEnabled(!isWorking);
            startBtn.setAlpha(isWorking ? 0.4f : 1.0f);
        }
        if (stopBtn != null) {
            stopBtn.setEnabled(isWorking);
            stopBtn.setAlpha(isWorking ? 1.0f : 0.4f);
        }

        SpannableStringBuilder sb1 = new SpannableStringBuilder();
        String stateSymbol = "[ ]";
        int stateColor = 0xFF757575;
        if ("LOGGING".equals(state)) {
            stateSymbol = "[ACTIVE]";
            stateColor = 0xFF2E7D32;
        } else if ("ERROR".equals(state)) {
            stateSymbol = "[ERR]";
            stateColor = 0xFFC62828;
        } else if ("RECONNECTING".equals(state) || "CONNECTING".equals(state)) {
            stateSymbol = "[TRYING]";
            stateColor = 0xFFF57C00;
        }
        
        sb1.append(stateSymbol).append(" ").append(state);
        sb1.setSpan(new ForegroundColorSpan(stateColor), 0, sb1.length(), 0);
        sb1.setSpan(new StyleSpan(Typeface.BOLD), 0, sb1.length(), 0);
        
        // Timeout Countdown logic
        if (isWorking) {
            long now = SystemClock.elapsedRealtime();
            long lastData = stats.lastDataRealtimeMs();
            long lastFix = stats.lastPositionRealtimeMs();
            
            // Check Data Timeout
            if (lastData > 0) {
                long diffMs = now - lastData;
                if (diffMs > 2000) {
                    long remainingSec = Math.max(0, 30 - (diffMs / 1000));
                    sb1.append(" (DATA PANIC IN ").append(String.valueOf(remainingSec)).append("s!)");
                }
            }
            
            // Check Fix Timeout (if we have data but no fix for long time)
            if (lastData > 0 && (now - lastData < 5000)) { // Only if data is flowing
                if (lastFix > 0) {
                    long fixDiffMs = now - lastFix;
                    if (fixDiffMs > 10000) { // Show warning after 10s no fix
                        long remainingFix = Math.max(0, 30 - (fixDiffMs / 1000));
                        sb1.append(" (FIX PANIC IN ").append(String.valueOf(remainingFix)).append("s!)");
                    }
                } else {
                    // Never had a fix
                    long sinceStart = now - lastData; // Approx
                    if (sinceStart > 30000) {
                         long remainingFirstFix = Math.max(0, 60 - (sinceStart / 1000));
                         sb1.append(" (NO FIX PANIC IN ").append(String.valueOf(remainingFirstFix)).append("s!)");
                    }
                }
            }
        }

        sb1.append(" | CONN: ").append(stats.connected() ? "OK" : "--");
        int fixStart = sb1.length();
        sb1.append(" | FIX: ").append(stats.fixValid() ? "YES" : "NO");
        if (stats.fixValid()) {
            sb1.setSpan(new ForegroundColorSpan(0xFF1565C0), fixStart + 8, sb1.length(), 0);
        }
        statusRow.setText(sb1);

        posRow.setText(String.format("LAT: %s, LON: %s", 
                stats.formatDouble(stats.latitude(), 6), 
                stats.formatDouble(stats.longitude(), 6)));

        if (openMapBtn != null) {
            boolean hasFix = stats.fixValid();
            openMapBtn.setVisibility(hasFix ? View.VISIBLE : View.GONE);
            if (hasFix) {
                openMapBtn.setOnClickListener(v -> openMap(stats.latitude(), stats.longitude()));
            }
        }

        detailRow.setText(String.format("ALT: %s m | SPD: %s km/h | CRS: %s", 
                stats.formatDouble(stats.altitudeMeters(), 1),
                stats.formatDouble(stats.speedKmh(), 1),
                stats.formatDouble(stats.courseDegrees(), 0)));

        String path = stats.currentFileName();
        if (!path.isEmpty()) {
            lastLogFile = path;
            if (path.contains("/")) path = path.substring(path.lastIndexOf('/') + 1);
            fileRow.setText("FILE: " + path + " (" + stats.formatBytes(stats.bytesWritten()) + ")");
        } else {
            fileRow.setText("FILE: (none)");
        }

        if (exportGpxBtn != null) {
            exportGpxBtn.setVisibility(!isWorking && !lastLogFile.isEmpty() ? View.VISIBLE : View.GONE);
        }

        debugRow.setText(String.format("Sats: %s/%s  HDOP: %s  Rate: %s",
                stats.formatInt(stats.satellitesUsed()),
                stats.formatInt(stats.satellitesVisible()),
                stats.formatDouble(stats.hdop(), 1),
                stats.formatDouble(stats.nmeaSentencesPerSecond(), 1) + "/s"));
    }

    private void openMap(double lat, double lon) {
        Uri gmmIntentUri = Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(Fix)");
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            toast("No map application found.");
        }
    }

    private void exportToGpxWithNotification() {
        if (lastLogFile.isEmpty()) return;
        File nmea = new File(lastLogFile);
        if (!nmea.exists()) {
            toast("Source file missing.");
            return;
        }

        try {
            File gpxInternal = NmeaToGpx.convert(nmea);
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                toast("Could not create Downloads directory.");
                return;
            }
            File gpxExternal = new File(downloadsDir, gpxInternal.getName());
            copyFile(gpxInternal, gpxExternal);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.addCompletedDownload(
                        gpxExternal.getName(),
                        "GPS Track Log",
                        true,
                        "application/gpx+xml",
                        gpxExternal.getAbsolutePath(),
                        gpxExternal.length(),
                        true
                );
                toast("GPX exported to Downloads folder!");
            }
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst);
             FileChannel inChannel = fis.getChannel();
             FileChannel outChannel = fos.getChannel()) {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static final class DeviceListItem {
        private final String name;
        private final String address;
        DeviceListItem(String name, String address) { this.name = name; this.address = address; }
        String name() { return name; }
        String address() { return address; }
        @Override public String toString() { return name + " (" + address + ")"; }
    }
}
