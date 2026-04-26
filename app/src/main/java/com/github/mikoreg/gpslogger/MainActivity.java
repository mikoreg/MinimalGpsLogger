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
import android.location.LocationManager;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_ALL = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private @Nullable GpsLoggerService boundService;
    private @Nullable Spinner deviceSpinner;
    private @Nullable ArrayAdapter<DeviceListItem> deviceAdapter;
    
    private @Nullable View exportOptionsContainer;
    private @Nullable Spinner rateSpinner;
    private @Nullable Spinner distSpinner;
    private @Nullable TextView permissionText;

    private @Nullable TextView statusRow;
    private @Nullable TextView connFixRow;
    private @Nullable TextView posRow;
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
        if (Build.VERSION.SDK_INT >= 23) {
            requestRuntimePermissionsIfNeeded();
        }
        refreshBondedDevices();
    }

    private void initViews() {
        View mainRoot = findViewById(R.id.mainRoot);
        if (mainRoot != null) {
            int pL = mainRoot.getPaddingLeft();
            int pT = mainRoot.getPaddingTop();
            int pR = mainRoot.getPaddingRight();
            int pB = mainRoot.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(pL, pT + bars.top, pR, pB + bars.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }

        statusRow = findViewById(R.id.statusRow);
        connFixRow = findViewById(R.id.connFixRow);
        posRow = findViewById(R.id.posRow);
        detailRow = findViewById(R.id.detailRow);
        debugRow = findViewById(R.id.debugRow);

        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        exportGpxBtn = findViewById(R.id.exportGpxBtn);
        openMapBtn = findViewById(R.id.openMapBtn);
        exportOptionsContainer = findViewById(R.id.exportOptionsContainer);

        deviceSpinner = findViewById(R.id.deviceSpinner);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<DeviceListItem>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (deviceSpinner != null) {
            deviceSpinner.setAdapter(deviceAdapter);
            deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    refreshPermissionText();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    refreshPermissionText();
                }
            });
        }

        rateSpinner = findViewById(R.id.rateSpinner);
        distSpinner = findViewById(R.id.distSpinner);
        setupExportSpinners();

        findViewById(R.id.refreshBtn).setOnClickListener(v -> refreshBondedDevices());
        findViewById(R.id.btSettingsBtn).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception e) {
                toast("Bluetooth settings not available.");
            }
        });

        if (startBtn != null) startBtn.setOnClickListener(v -> startLogging());
        if (stopBtn != null) stopBtn.setOnClickListener(v -> stopLogging());
        if (exportGpxBtn != null) exportGpxBtn.setOnClickListener(v -> exportToGpxWithNotification());
        
        permissionText = findViewById(R.id.permissionText);
        refreshPermissionText();
        updateStatsUi(NmeaStats.idle());
    }

    private void setupExportSpinners() {
        if (rateSpinner != null) {
            List<ExportRate> rates = new ArrayList<>();
            rates.add(new ExportRate("All points (native)", 0));
            rates.add(new ExportRate("10 Hz (0.1s)", 100));
            rates.add(new ExportRate("1 Hz (1.0s)", 1000));
            rates.add(new ExportRate("Every 5s", 5000));
            rates.add(new ExportRate("Every 10s", 10000));
            ArrayAdapter<ExportRate> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, rates);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            rateSpinner.setAdapter(adapter);
        }
        if (distSpinner != null) {
            List<ExportDist> dists = new ArrayList<>();
            dists.add(new ExportDist("No motion filter", 0));
            dists.add(new ExportDist("Min. 1 meter", 1f));
            dists.add(new ExportDist("Min. 3 meters", 3f));
            dists.add(new ExportDist("Min. 10 meters", 10f));
            dists.add(new ExportDist("Min. 50 meters", 50f));
            ArrayAdapter<ExportDist> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dists);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            distSpinner.setAdapter(adapter);
        }
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
        if (Build.VERSION.SDK_INT < 23) return;

        List<String> toRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), REQUEST_ALL);
        }
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        boolean connectOk = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        boolean scanOk = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        return connectOk && scanOk;
    }

    private boolean hasFineLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissionText() {
        if (permissionText == null) return;
        boolean bluetoothOk = hasBluetoothConnectPermission();
        boolean locationOk = hasFineLocationPermission();
        
        DeviceListItem selected = getSelectedDevice();
        boolean internalSource = selected != null && GpsLoggerService.SOURCE_INTERNAL.equals(selected.address());
        
        boolean relevantPermissionsOk = internalSource ? locationOk : bluetoothOk;
        if (relevantPermissionsOk) {
            permissionText.setText("Permissions: OK");
            permissionText.setTextColor(0xFF888888);
        } else {
            StringBuilder missing = new StringBuilder("Missing permission:");
            if (!internalSource && !bluetoothOk) missing.append(" BLUETOOTH_CONNECT");
            if (internalSource && !locationOk) missing.append(" ACCESS_FINE_LOCATION");
            permissionText.setText(missing.toString());
            permissionText.setTextColor(0xFFFF0000);
        }
    }

    private void refreshBondedDevices() {
        if (deviceAdapter == null) return;
        deviceAdapter.clear();
        
        deviceAdapter.add(new DeviceListItem("INTERNAL GPS (Built-in Chip)", GpsLoggerService.SOURCE_INTERNAL));
        deviceAdapter.add(new DeviceListItem("USB GPS (Not supported yet)", "usb"));
        
        if (hasBluetoothConnectPermission()) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                try {
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    for (BluetoothDevice device : bonded) {
                        String name = device.getName();
                        deviceAdapter.add(new DeviceListItem("BT: " + (name == null ? "Unknown" : name), device.getAddress()));
                    }
                } catch (SecurityException ignored) {}
            }
        }
        deviceAdapter.notifyDataSetChanged();
        refreshPermissionText();
    }

    private @Nullable DeviceListItem getSelectedDevice() {
        if (deviceSpinner == null || deviceSpinner.getSelectedItem() == null) return null;
        return (DeviceListItem) deviceSpinner.getSelectedItem();
    }

    private void startLogging() {
        DeviceListItem selected = getSelectedDevice();
        if (selected == null) {
            toast("Select a GPS source first.");
            return;
        }
        
        String sourceType = GpsLoggerService.SOURCE_INTERNAL.equals(selected.address()) 
                ? GpsLoggerService.SOURCE_INTERNAL 
                : GpsLoggerService.SOURCE_BLUETOOTH;
                
        if ("usb".equals(selected.address())) {
            toast("USB GPS is not implemented yet.");
            return;
        }

        if (GpsLoggerService.SOURCE_BLUETOOTH.equals(sourceType) && !hasBluetoothConnectPermission()) {
            requestRuntimePermissionsIfNeeded();
            return;
        }
        if (GpsLoggerService.SOURCE_INTERNAL.equals(sourceType) && !hasFineLocationPermission()) {
            requestRuntimePermissionsIfNeeded();
            return;
        }
        if (GpsLoggerService.SOURCE_INTERNAL.equals(sourceType) && !isGpsProviderEnabled()) {
            toast("Turn on GPS/location in Android settings.");
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception e) {
                toast("Location settings not available.");
            }
            return;
        }

        Intent intent = new Intent(this, GpsLoggerService.class);
        intent.setAction(GpsLoggerService.ACTION_START);
        intent.putExtra(GpsLoggerService.EXTRA_SOURCE_TYPE, sourceType);
        if (GpsLoggerService.SOURCE_BLUETOOTH.equals(sourceType)) {
            intent.putExtra(GpsLoggerService.EXTRA_DEVICE_ADDRESS, selected.address());
            intent.putExtra(GpsLoggerService.EXTRA_DEVICE_NAME, selected.name());
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean isGpsProviderEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException ex) {
            return false;
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
        if (statusRow == null || connFixRow == null || posRow == null || detailRow == null || debugRow == null) return;

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

        long now = SystemClock.elapsedRealtime();
        long lastData = stats.lastDataRealtimeMs();
        long lastFix = stats.lastPositionRealtimeMs();
        long sessionStart = stats.sessionStartRealtimeMs();

        // --- LINE 1: STATUS & PANICS ---
        SpannableStringBuilder sbStatus = new SpannableStringBuilder();
        String stateSymbol = "[ ]";
        int stateColor = 0xFF000000; // Black
        if ("LOGGING".equals(state)) {
            stateSymbol = "[ACTIVE]";
            stateColor = 0xFF2E7D32; // Green
        } else if ("ERROR".equals(state)) {
            stateSymbol = "[ERR]";
            stateColor = 0xFFC62828; // Red
        } else if ("RECONNECTING".equals(state) || "CONNECTING".equals(state)) {
            stateSymbol = "[TRYING]";
            stateColor = 0xFFF57C00; // Orange
        }
        
        sbStatus.append(stateSymbol).append(" ").append(state);
        sbStatus.setSpan(new ForegroundColorSpan(stateColor), 0, sbStatus.length(), 0);
        sbStatus.setSpan(new StyleSpan(Typeface.BOLD), 0, sbStatus.length(), 0);

        if (isWorking) {
            // Data Panic
            if (lastData > 0) {
                long dataDiffMs = now - lastData;
                if (dataDiffMs > 2000) {
                    long rem = Math.max(0, 30 - (dataDiffMs / 1000));
                    int start = sbStatus.length();
                    sbStatus.append(" (DATA PANIC IN ").append(String.valueOf(rem)).append("s!)");
                    sbStatus.setSpan(new ForegroundColorSpan(0xFFC62828), start, sbStatus.length(), 0);
                }
            }
            // Fix Panic
            long fixReference = lastFix > 0 ? lastFix : sessionStart;
            if (fixReference > 0) {
                long fixDiffMs = now - fixReference;
                if (fixDiffMs > 5000) {
                    long rem = Math.max(0, 30 - (fixDiffMs / 1000));
                    int start = sbStatus.length();
                    String label = lastFix > 0 ? "FIX PANIC" : "NO FIX PANIC";
                    sbStatus.append(" (").append(label).append(" IN ").append(String.valueOf(rem)).append("s!)");
                    sbStatus.setSpan(new ForegroundColorSpan(0xFFC62828), start, sbStatus.length(), 0);
                }
            }
        }
        statusRow.setText(sbStatus);

        // --- LINE 2: CONN & FIX ---
        SpannableStringBuilder sbConnFix = new SpannableStringBuilder();
        sbConnFix.append("CONN: ");
        int connStart = sbConnFix.length();
        sbConnFix.append(stats.connected() ? "OK" : "--");
        sbConnFix.setSpan(new ForegroundColorSpan(stats.connected() ? 0xFF2E7D32 : 0xFFC62828), connStart, sbConnFix.length(), 0);
        
        sbConnFix.append(" | FIX: ");
        int fixStatusStart = sbConnFix.length();
        sbConnFix.append(stats.fixValid() ? "YES" : "NO");
        sbConnFix.setSpan(new ForegroundColorSpan(stats.fixValid() ? 0xFF2E7D32 : 0xFFC62828), fixStatusStart, sbConnFix.length(), 0);
        connFixRow.setText(sbConnFix);

        // --- LINE 3: LAT/LON ---
        posRow.setText(String.format("LAT:%s LON:%s",
                stats.formatDouble(stats.latitude(), 6), 
                stats.formatDouble(stats.longitude(), 6)));

        if (openMapBtn != null) {
            boolean hasFix = stats.fixValid();
            openMapBtn.setVisibility(hasFix ? View.VISIBLE : View.GONE);
            if (hasFix) {
                openMapBtn.setOnClickListener(v -> openMap(stats.latitude(), stats.longitude()));
            }
        }

        // --- LINE 4: ALT/SPD/CRS ---
        detailRow.setText(String.format("ALT: %s m | SPD: %s km/h | CRS: %s", 
                stats.formatDouble(stats.altitudeMeters(), 1),
                stats.formatDouble(stats.speedKmh(), 1),
                stats.formatDouble(stats.courseDegrees(), 0)));

        // --- LINE 5: TIME | SIZE ---
        StringBuilder sbStats = new StringBuilder();
        if (sessionStart > 0 && isWorking) {
            long durationSec = (now - sessionStart) / 1000;
            long h = durationSec / 3600;
            long m = (durationSec % 3600) / 60;
            long s = durationSec % 60;
            sbStats.append(String.format(Locale.US, "TIME: %02d:%02d:%02d | ", h, m, s));
        } else {
            sbStats.append("TIME: --:--:-- | ");
        }
        
        String path = stats.currentFileName();
        if (!path.isEmpty()) {
            lastLogFile = path;
            sbStats.append("LOG SIZE: ").append(stats.formatBytes(stats.bytesWritten()));
        } else {
            sbStats.append("LOG SIZE: 0 B");
        }
        debugRow.setText(sbStats.toString());

        // Show/Hide export area
        if (exportOptionsContainer != null) {
            exportOptionsContainer.setVisibility(!isWorking && !lastLogFile.isEmpty() ? View.VISIBLE : View.GONE);
        }
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

        long intervalMs = 0;
        if (rateSpinner != null) {
            intervalMs = ((ExportRate) rateSpinner.getSelectedItem()).intervalMs;
        }
        float minDist = 0;
        if (distSpinner != null) {
            minDist = ((ExportDist) distSpinner.getSelectedItem()).minDistMeters;
        }

        try {
            File gpxInternal = NmeaToGpx.convert(nmea, intervalMs, minDist);
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
        @Override public String toString() { return name; }
    }

    private static final class ExportRate {
        final String label;
        final long intervalMs;
        ExportRate(String label, long intervalMs) { this.label = label; this.intervalMs = intervalMs; }
        @Override public String toString() { return label; }
    }

    private static final class ExportDist {
        final String label;
        final float minDistMeters;
        ExportDist(String label, float minDistMeters) { this.label = label; this.minDistMeters = minDistMeters; }
        @Override public String toString() { return label; }
    }
}
