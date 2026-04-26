package com.github.mikoreg.gpslogger;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Handler;

@TargetApi(24)
final class InternalNmeaApi24 {
    interface Sink {
        void onNmea(long timestamp, String nmea);
    }

    private final LocationManager locationManager;
    private final OnNmeaMessageListener listener;

    InternalNmeaApi24(LocationManager locationManager, Sink sink) {
        this.locationManager = locationManager;
        this.listener = new OnNmeaMessageListener() {
            @Override
            public void onNmeaMessage(String message, long timestamp) {
                sink.onNmea(timestamp, message);
            }
        };
    }

    @SuppressLint("MissingPermission")
    boolean register(Handler handler) {
        return locationManager.addNmeaListener(listener, handler);
    }

    void unregister() {
        locationManager.removeNmeaListener(listener);
    }
}
