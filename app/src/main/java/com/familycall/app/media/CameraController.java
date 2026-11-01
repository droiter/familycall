package com.familycall.app.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Minimal Camera2 session that feeds both the encoder surface and a local preview. */
public final class CameraController {
    private static final String TAG = "CameraController";

    public interface Listener {
        /**
         * @param previewRotation rotation for the local preview (front camera
         *                        already mirror-compensated)
         * @param streamRotation  rotation to send to the peer for the raw
         *                        (non-mirrored) stream
         * @param mirror          whether the local preview should be mirrored
         */
        void onCameraReady(int previewRotation, int streamRotation, boolean mirror);

        void onCameraError(String message);
    }

    private final Context context;
    private final int width;
    private final int height;
    private final Listener listener;
    private HandlerThread thread;
    private Handler handler;
    private CameraManager manager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface encoderSurface;
    private SurfaceTexture previewTexture;
    private Surface previewSurface;
    private volatile boolean front = true;
    private volatile boolean running;
    private volatile boolean released;
    private String cameraId;
    private int sensorOrientation;
    private boolean mirror;
    private Range<Integer> fpsRange;
    private int[] afModes;
    private boolean stabilization;

    public CameraController(Context context, int width, int height, Listener listener) {
        this.context = context.getApplicationContext();
        this.width = width;
        this.height = height;
        this.listener = listener;
    }

    public void start(Surface encoderSurface, SurfaceTexture preview, boolean useFront) {
        this.encoderSurface = encoderSurface;
        this.previewTexture = preview;
        this.front = useFront;
        released = false;
        thread = new HandlerThread("camera2");
        thread.start();
        handler = new Handler(thread.getLooper());
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        handler.post(this::open);
    }

    public boolean isFront() {
        return front;
    }

    public void switchCamera() {
        if (handler == null) return;
        try {
            if (manager != null && manager.getCameraIdList().length < 2) return;
        } catch (Exception ignored) {
            return;
        }
        handler.post(() -> {
            if (!running) {
                front = !front;
                return;
            }
            front = !front;
            closeSession();
            handler.postDelayed(this::open, 300);
        });
    }

    public void updatePreview(SurfaceTexture texture) {
        if (this.previewTexture == texture) return;
        this.previewTexture = texture;
        if (handler == null || !running) return;
        handler.post(() -> {
            if (!running || encoderSurface == null) return;
            closeSession();
            handler.postDelayed(this::open, 300);
        });
    }

    private void open() {
        if (released) return;
        try {
            cameraId = findCamera(front);
            if (cameraId == null) {
                cameraId = findCamera(!front);
                front = !front;
            }
            if (cameraId == null) {
                listener.onCameraError("没有可用的摄像头");
                return;
            }
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 0 : so;
            Boolean isFront = cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
            front = Boolean.TRUE.equals(isFront);
            mirror = front;
            Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            fpsRange = chooseFps(ranges);
            afModes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int[] stab = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            stabilization = false;
            if (stab != null) {
                for (int mode : stab) {
                    if (mode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                        stabilization = true;
                        break;
                    }
                }
            }
            if (previewTexture != null) {
                previewTexture.setDefaultBufferSize(width, height);
                previewSurface = new Surface(previewTexture);
            }
            manager.openCamera(cameraId, stateCallback, handler);
        } catch (SecurityException e) {
            listener.onCameraError("需要摄像头权限才能视频通话");
        } catch (Exception e) {
            Log.e(TAG, "open camera", e);
            listener.onCameraError("摄像头打开失败");
        }
    }

    private Range<Integer> chooseFps(Range<Integer>[] ranges) {
        if (ranges == null) return null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= 24 && r.getUpper() >= 24) return r;
        }
        return ranges[0];
    }

    private String findCamera(boolean wantFront) {
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == (wantFront
                        ? CameraCharacteristics.LENS_FACING_FRONT
                        : CameraCharacteristics.LENS_FACING_BACK)) {
                    return id;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            if (released) {
                camera.close();
                return;
            }
            device = camera;
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            device = null;
            listener.onCameraError("摄像头已断开");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            device = null;
            listener.onCameraError("摄像头异常(" + error + ")");
        }
    };

    private void createSession() {
        try {
            List<Surface> surfaces = new ArrayList<>();
            if (encoderSurface != null && encoderSurface.isValid()) surfaces.add(encoderSurface);
            if (previewSurface != null && previewSurface.isValid()) surfaces.add(previewSurface);
            if (surfaces.isEmpty()) {
                listener.onCameraError("摄像头输出目标不可用");
                return;
            }
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession camSession) {
                    if (released) return;
                    session = camSession;
                    startPreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession camSession) {
                    listener.onCameraError("无法建立摄像头预览");
                }
            }, handler);
        } catch (Exception e) {
            Log.e(TAG, "create session", e);
            listener.onCameraError("摄像头会话失败");
        }
    }

    private void startPreview() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            if (encoderSurface != null) b.addTarget(encoderSurface);
            if (previewSurface != null) b.addTarget(previewSurface);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            if (afModes != null) {
                boolean hasContinuous = false;
                for (int mode : afModes) {
                    if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                        hasContinuous = true;
                        break;
                    }
                }
                if (hasContinuous) {
                    b.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                }
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            if (stabilization) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            }
            session.setRepeatingRequest(b.build(), null, handler);
            running = true;
            notifyOrientation();
        } catch (Exception e) {
            Log.e(TAG, "start preview", e);
            listener.onCameraError("摄像头预览失败");
        }
    }

    /** Recompute and re-publish rotation after a display rotation change. */
    public void refreshOrientation() {
        if (handler == null) return;
        handler.post(() -> {
            if (running) notifyOrientation();
        });
    }

    private void notifyOrientation() {
        int displayRotation = displayRotationDegrees();
        int previewRotation;
        int streamRotation;
        if (front) {
            int base = (sensorOrientation + displayRotation) % 360;
            previewRotation = (360 - base) % 360;
            streamRotation = base;
        } else {
            previewRotation = (sensorOrientation - displayRotation + 360) % 360;
            streamRotation = previewRotation;
        }
        mirror = front;
        Log.i(TAG, "orientation display=" + displayRotation + " sensor=" + sensorOrientation
                + " front=" + front + " preview=" + previewRotation + " stream=" + streamRotation);
        listener.onCameraReady(previewRotation, streamRotation, mirror);
    }

    private int displayRotationDegrees() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return 0;
            switch (display.getRotation()) {
                case Surface.ROTATION_90:
                    return 90;
                case Surface.ROTATION_180:
                    return 180;
                case Surface.ROTATION_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private void closeSession() {
        running = false;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        device = null;
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Exception ignored) {
            }
            previewSurface = null;
        }
    }

    public void stop() {
        released = true;
        running = false;
        if (handler != null) {
            handler.post(this::closeSession);
        }
        if (thread != null) {
            try {
                thread.quitSafely();
                thread.join(800);
            } catch (Exception ignored) {
            }
            thread = null;
            handler = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        encoderSurface = null;
        previewTexture = null;
    }
}
