package com.github.mikoreg.gpslogger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_ALL = 1000;
    private static final int INITIAL_LOAD_LIMIT = 20;
    private static final String ACTION_USB_PERMISSION = "com.github.mikoreg.gpslogger.action.USB_PERMISSION";
    private static final int FLAG_MUTABLE = 0x02000000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private @Nullable String pendingUsbStartDeviceName;

    private @Nullable GpsLoggerService boundService;
    private @Nullable Spinner deviceSpinner;
    private @Nullable ArrayAdapter<DeviceListItem> deviceAdapter;
    
    private @Nullable View exportOptionsContainer;
    private @Nullable Spinner rateSpinner;
    private @Nullable Spinner distSpinner;

    private @Nullable TextView statusRow;
    private @Nullable TextView connFixRow;
    private @Nullable TextView posRow;
    private @Nullable TextView detailRow;
    private @Nullable TextView debugRow;

    private @Nullable Button startBtn;
    private @Nullable Button stopBtn;
    private @Nullable Button exportGpxBtn;
    private @Nullable Button openMapBtn;

    // History elements
    private @Nullable LinearLayout historyContainer;
    private @Nullable Button loadMoreBtn;
    private @Nullable View historyActions;
    private @Nullable CheckBox historySelectAll;
    
    private final List<File> logFiles = new ArrayList<>();
    private final Set<File> selectedFiles = new HashSet<>();
    private int loadLimit = INITIAL_LOAD_LIMIT;

    private boolean wasLogging = false;
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

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                pendingUsbStartDeviceName = null;
                refreshBondedDevices();
                return;
            }
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                String pendingDeviceName = pendingUsbStartDeviceName;
                pendingUsbStartDeviceName = null;
                refreshBondedDevices();
                if (granted && device != null && device.getDeviceName().equals(pendingDeviceName)) {
                    startUsbLogging(device);
                } else {
                    toast("USB permission denied.");
                }
            }
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
        refreshHistory();
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

        // History Init
        historyContainer = findViewById(R.id.historyContainer);
        loadMoreBtn = findViewById(R.id.loadMoreBtn);
        historyActions = findViewById(R.id.historyActions);
        historySelectAll = findViewById(R.id.historySelectAll);

        if (loadMoreBtn != null) loadMoreBtn.setOnClickListener(v -> {
            loadLimit += 20;
            renderHistoryItems();
        });
        if (historySelectAll != null) historySelectAll.setOnCheckedChangeListener((v, checked) -> {
            selectedFiles.clear();
            if (checked) selectedFiles.addAll(logFiles);
            renderHistoryItems();
        });
        findViewById(R.id.historyExportBtn).setOnClickListener(v -> exportSelectedLogs());
        findViewById(R.id.historyDeleteBtn).setOnClickListener(v -> confirmDeleteLogs());
        
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
        IntentFilter usbFilter = new IntentFilter(ACTION_USB_PERMISSION);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(
                this,
                usbPermissionReceiver,
                usbFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        handler.post(uiRefreshRunnable);
        refreshHistory();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(uiRefreshRunnable);
        if (boundService != null) {
            unbindService(serviceConnection);
            boundService = null;
        }
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (IllegalArgumentException ignored) {}
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshBondedDevices();
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

    private void refreshBondedDevices() {
        if (deviceAdapter == null) return;
        deviceAdapter.clear();
        
        deviceAdapter.add(new DeviceListItem(
                "INTERNAL GPS (Built-in Chip)",
                GpsLoggerService.SOURCE_INTERNAL,
                GpsLoggerService.SOURCE_INTERNAL));
        addUsbDevicesToAdapter();
        
        if (hasBluetoothConnectPermission()) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                try {
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    for (BluetoothDevice device : bonded) {
                        String name = device.getName();
                        deviceAdapter.add(new DeviceListItem(
                                "BT: " + (name == null ? "Unknown" : name),
                                device.getAddress(),
                                GpsLoggerService.SOURCE_BLUETOOTH));
                    }
                } catch (SecurityException ignored) {}
            }
        }
        deviceAdapter.notifyDataSetChanged();
    }

    private void addUsbDevicesToAdapter() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null || deviceAdapter == null) {
            if (deviceAdapter != null) {
                deviceAdapter.add(new DeviceListItem(
                        "USB GPS (USB host unavailable)",
                        "",
                        GpsLoggerService.SOURCE_USB));
            }
            return;
        }
        boolean foundUsbDevice = false;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            foundUsbDevice = true;
            deviceAdapter.add(new DeviceListItem(
                    "USB: " + describeUsbDevice(device, usbManager.hasPermission(device)),
                    device.getDeviceName(),
                    GpsLoggerService.SOURCE_USB));
        }
        if (!foundUsbDevice) {
            deviceAdapter.add(new DeviceListItem(
                    "USB GPS (not connected)",
                    "",
                    GpsLoggerService.SOURCE_USB));
        }
    }

    private static String describeUsbDevice(UsbDevice device, boolean hasPermission) {
        StringBuilder name = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 21) {
            String product = device.getProductName();
            if (product != null && !product.isEmpty()) {
                name.append(product);
            }
        }
        if (name.length() == 0) {
            name.append(String.format(Locale.US, "VID:%04X PID:%04X", device.getVendorId(), device.getProductId()));
        }
        if (!hasPermission) {
            name.append(" (permission needed)");
        }
        return name.toString();
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
        
        String sourceType = selected.sourceType();

        if (GpsLoggerService.SOURCE_USB.equals(sourceType)) {
            if (selected.address().isEmpty()) {
                toast("USB GPS is not connected.");
                refreshBondedDevices();
                return;
            }
            startUsbLoggingWithPermission(selected.address());
            return;
        }

        // Check permissions and alert if missing
        boolean bluetoothMissing = GpsLoggerService.SOURCE_BLUETOOTH.equals(sourceType) && !hasBluetoothConnectPermission();
        boolean locationMissing = GpsLoggerService.SOURCE_INTERNAL.equals(sourceType) && !hasFineLocationPermission();

        if (bluetoothMissing || locationMissing) {
            new AlertDialog.Builder(this)
                .setTitle("Permissions missing")
                .setMessage("Required permissions for " + sourceType + " source are missing. Please allow them in settings.")
                .setPositiveButton("Settings", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private void startUsbLoggingWithPermission(String usbDeviceName) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            toast("USB is not available on this device.");
            return;
        }
        UsbDevice device = findUsbDevice(usbManager, usbDeviceName);
        if (device == null) {
            toast("USB GPS device is no longer connected.");
            refreshBondedDevices();
            return;
        }
        if (!usbManager.hasPermission(device)) {
            pendingUsbStartDeviceName = device.getDeviceName();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= FLAG_MUTABLE;
            }
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    flags);
            usbManager.requestPermission(device, permissionIntent);
            toast("Allow USB GPS permission and logging will start.");
            return;
        }
        startUsbLogging(device);
    }

    private void startUsbLogging(UsbDevice device) {
        Intent intent = new Intent(this, GpsLoggerService.class);
        intent.setAction(GpsLoggerService.ACTION_START);
        intent.putExtra(GpsLoggerService.EXTRA_SOURCE_TYPE, GpsLoggerService.SOURCE_USB);
        intent.putExtra(GpsLoggerService.EXTRA_DEVICE_ADDRESS, device.getDeviceName());
        intent.putExtra(GpsLoggerService.EXTRA_DEVICE_NAME, describeUsbDevice(device, true));
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private static @Nullable UsbDevice findUsbDevice(UsbManager usbManager, String usbDeviceName) {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (usbDeviceName.equals(device.getDeviceName())) {
                return device;
            }
        }
        return null;
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

        if (!isWorking && wasLogging) {
            wasLogging = false;
            refreshHistory();
            // Automatically select the most recent file after logging stops
            if (!logFiles.isEmpty()) {
                File latest = logFiles.get(0);
                lastLogFile = latest.getAbsolutePath();
                selectedFiles.add(latest);
                renderHistoryItems();
                updateExportButtonState(false);
            }
        }
        if (isWorking) {
            wasLogging = true;
        }

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
        long age = stats.lastSentenceAgeMs();

        boolean dataIsFresh = stats.connected() && age >= 0 && age < 3000;
        boolean fixIsFresh = stats.connected() && age >= 0 && age < 10000;
        boolean uiFixValid = stats.fixValid() && fixIsFresh;

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
        sbConnFix.append(uiFixValid ? "YES" : "NO");
        sbConnFix.setSpan(new ForegroundColorSpan(uiFixValid ? 0xFF2E7D32 : 0xFFC62828), fixStatusStart, sbConnFix.length(), 0);
        connFixRow.setText(sbConnFix);

        // --- LINE 3: LAT/LON ---
        posRow.setText(String.format("LAT:%s LON:%s",
                stats.formatDouble(stats.latitude(), 6), 
                stats.formatDouble(stats.longitude(), 6)));

        if (openMapBtn != null) {
            openMapBtn.setVisibility(uiFixValid ? View.VISIBLE : View.GONE);
            if (uiFixValid) {
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

        double displayFreq = dataIsFresh ? stats.nmeaSentencesPerSecond() : 0.0;
        sbStats.append(String.format(Locale.US, "FREQ: %s Hz | ",
                stats.formatDouble(displayFreq, 1)));
        
        String path = stats.currentFileName();
        if (!path.isEmpty()) {
            sbStats.append("LOG SIZE: ").append(stats.formatBytes(stats.bytesWritten()));
        } else {
            sbStats.append("LOG SIZE: 0 B");
        }
        debugRow.setText(sbStats.toString());

        // Show/Hide export area permanently if files exist
        if (exportOptionsContainer != null) {
            exportOptionsContainer.setVisibility(!logFiles.isEmpty() ? View.VISIBLE : View.GONE);
        }
        updateExportButtonState(isWorking);
    }

    private void updateExportButtonState(boolean isWorking) {
        if (exportGpxBtn != null) {
            boolean hasSelected = !lastLogFile.isEmpty();
            boolean canExport = hasSelected && !isWorking;
            exportGpxBtn.setEnabled(canExport);
            if (canExport) {
                exportGpxBtn.setAlpha(1.0f);
                exportGpxBtn.setBackgroundColor(0xFF0097A7); // Turquoise/Cyan 700
                exportGpxBtn.setTextColor(0xFFFFFFFF);
            } else {
                exportGpxBtn.setAlpha(0.4f);
                exportGpxBtn.setBackgroundColor(0xFFBBBBBB); // Gray
                exportGpxBtn.setTextColor(0xFF888888);
            }
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
        if (lastLogFile.isEmpty()) {
            toast("No file selected for export.");
            return;
        }
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
            if (copyToDownloads(gpxInternal, "application/gpx+xml")) {
                toast("GPX exported to Downloads folder!");
            }
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    // --- HISTORY LOGIC ---

    private void refreshHistory() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File tracksDir = new File(base, "tracks");

        logFiles.clear();
        selectedFiles.clear();
        if (tracksDir.exists() && tracksDir.isDirectory()) {
            File[] files = tracksDir.listFiles((dir, name) -> name.endsWith(".nmea"));
            if (files != null) {
                Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                logFiles.addAll(Arrays.asList(files));
            }
        }
        renderHistoryItems();
    }

    private void renderHistoryItems() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        
        LayoutInflater inflater = LayoutInflater.from(this);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        
        int count = Math.min(logFiles.size(), loadLimit);
        for (int i = 0; i < count; i++) {
            File file = logFiles.get(i);
            View row = inflater.inflate(R.layout.log_item, historyContainer, false);
            
            CheckBox cb = row.findViewById(R.id.logCheckBox);
            TextView name = row.findViewById(R.id.logFileName);
            TextView info = row.findViewById(R.id.logFileInfo);
            ImageButton share = row.findViewById(R.id.shareBtn);
            
            name.setText(file.getName());
            info.setText(String.format(Locale.US, "%s | %.1f KB", 
                    dateFormat.format(new Date(file.lastModified())),
                    file.length() / 1024.0));
            
            cb.setChecked(selectedFiles.contains(file));
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selectedFiles.add(file); else selectedFiles.remove(file);
                updateHistoryActionsVisibility();
            });
            
            // Selection logic for GPX export
            if (file.getAbsolutePath().equals(lastLogFile)) {
                row.setBackgroundColor(0x15000000); // Light highlight
            } else {
                row.setBackgroundColor(0x00000000);
            }

            row.setOnClickListener(v -> {
                android.util.Log.d("GPSLogger", "Row clicked: " + file.getName());
                if (file.getAbsolutePath().equals(lastLogFile)) {
                    lastLogFile = ""; // Deselect if already selected
                    selectedFiles.remove(file); // Also uncheck the checkbox
                } else {
                    lastLogFile = file.getAbsolutePath();
                    selectedFiles.add(file); // Also check the checkbox
                }
                android.util.Log.d("GPSLogger", "New lastLogFile: " + lastLogFile);
                renderHistoryItems(); // Re-render to show selection highlight and checkbox state
                GpsLoggerService service = boundService;
                boolean isWorking = service != null && !"IDLE".equals(service.stats().state()) && !"STOPPED".equals(service.stats().state());
                updateExportButtonState(isWorking);
            });

            // Ensure views inside the row don't consume the click
            cb.setFocusable(false);
            cb.setClickable(false);
            
            // Allow share to be clicked independently
            share.setOnClickListener(v -> {
                android.util.Log.d("GPSLogger", "Share clicked: " + file.getName());
                shareFile(file);
            });
            
            historyContainer.addView(row);
        }
        
        if (loadMoreBtn != null) {
            loadMoreBtn.setVisibility(logFiles.size() > loadLimit ? View.VISIBLE : View.GONE);
        }
        updateHistoryActionsVisibility();
    }

    private void updateHistoryActionsVisibility() {
        if (historyActions != null) {
            historyActions.setVisibility(selectedFiles.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void exportSelectedLogs() {
        int success = 0;
        for (File f : selectedFiles) {
            if (copyToDownloads(f, "application/octet-stream")) success++;
        }
        toast("Exported " + success + " logs to Downloads.");
        selectedFiles.clear();
        if (historySelectAll != null) historySelectAll.setChecked(false);
        renderHistoryItems();
    }

    private void confirmDeleteLogs() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Logs")
                .setMessage("Delete " + selectedFiles.size() + " files?")
                .setPositiveButton("Delete", (d, w) -> {
                    for (File f : selectedFiles) {
                        if (f.getAbsolutePath().equals(lastLogFile)) lastLogFile = "";
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                    refreshHistory();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share NMEA Log"));
        } catch (Exception e) {
            toast("Share failed: " + e.getMessage());
        }
    }

    private boolean copyToDownloads(File src, String mimeType) {
        try {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) return false;
            File dst = new File(downloadsDir, src.getName());
            
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(dst);
                 FileChannel inChannel = fis.getChannel();
                 FileChannel outChannel = fos.getChannel()) {
                inChannel.transferTo(0, inChannel.size(), outChannel);
            }

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.addCompletedDownload(dst.getName(), "GPS Log", true, mimeType, dst.getAbsolutePath(), dst.length(), true);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static final class DeviceListItem {
        private final String name;
        private final String address;
        private final String sourceType;
        DeviceListItem(String name, String address, String sourceType) {
            this.name = name;
            this.address = address;
            this.sourceType = sourceType;
        }
        String name() { return name; }
        String address() { return address; }
        String sourceType() { return sourceType; }
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
