package com.familycall.app.media;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Derives the physical device rotation from the gravity sensor so the camera
 * preview and the outgoing stream follow how the phone is actually held, even
 * when the activity is locked to a fixed orientation or the display rotation
 * has not caught up yet.
 */
public final class GravityOrientation implements SensorEventListener {
    private static final String TAG = "GravityOrientation";
    private static final long STABLE_MS = 350;

    public interface Listener {
        void onDeviceRotation(int clockwiseDegrees);
    }

    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int rotation = -1;
    private int pending = -1;
    private long pendingSince;
    private boolean started;

    public GravityOrientation(Context context, Listener listener) {
        this.listener = listener;
        this.sensorManager = (SensorManager) context.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        Sensor s = null;
        if (sensorManager != null) {
            s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (s == null) s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        this.sensor = s;
    }

    public void start() {
        if (started || sensorManager == null || sensor == null) {
            if (sensor == null) Log.w(TAG, "no gravity/accelerometer sensor");
            return;
        }
        started = true;
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, main);
        Log.i(TAG, "started sensor=" + sensor.getName());
    }

    public void stop() {
        if (!started) return;
        started = false;
        try {
            sensorManager.unregisterListener(this);
        } catch (Exception ignored) {
        }
    }

    /** @return clockwise rotation 0/90/180/270, or -1 before the first reading. */
    public int getRotation() {
        return rotation;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        if (Math.abs(x) < 1.5f && Math.abs(y) < 1.5f) return;

        int candidate;
        if (Math.abs(x) > Math.abs(y)) {
            // Accelerometer/gravity reports the support force. With the device
            // rotated clockwise (top to the right), +x points down, so x < 0.
            candidate = x < 0 ? 90 : 270;
        } else {
            candidate = y > 0 ? 0 : 180;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (candidate == rotation) {
            pending = -1;
            return;
        }
        if (candidate != pending) {
            pending = candidate;
            pendingSince = now;
            return;
        }
        if (now - pendingSince >= STABLE_MS) {
            rotation = candidate;
            pending = -1;
            Log.i(TAG, "device rotation=" + rotation + " gx=" + String.format(java.util.Locale.US, "%.2f", x)
                    + " gy=" + String.format(java.util.Locale.US, "%.2f", y));
            main.post(() -> listener.onDeviceRotation(rotation));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
