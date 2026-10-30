package com.familycall.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.familycall.app.R;
import com.familycall.app.data.Prefs;

import java.util.Locale;

public final class UiUtils {
    private UiUtils() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void circle(View view, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        view.setBackground(d);
    }

    public static void tint(ImageView view, int color) {
        view.setColorFilter(color);
    }

    public static void toast(Context context, String message) {
        if (context == null || message == null) return;
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void toastLong(Context context, String message) {
        if (context == null || message == null) return;
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    public static String duration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /**
     * Rotates/mirrors a TextureView by resizing the view to the raw video aspect
     * and rotating the whole view. This avoids stretching and does not depend on
     * TextureView's transform matrix semantics.
     */
    public static void layoutRotatedTexture(android.view.TextureView tv, int containerW, int containerH,
                                            int videoW, int videoH, int rotation, boolean mirror) {
        if (tv == null || containerW <= 0 || containerH <= 0 || videoW <= 0 || videoH <= 0) return;
        int rot = ((rotation % 360) + 360) % 360;
        boolean swap = rot == 90 || rot == 270;
        float scale = swap
                ? Math.max(containerW / (float) videoH, containerH / (float) videoW)
                : Math.max(containerW / (float) videoW, containerH / (float) videoH);
        int vw = Math.max(1, Math.round(videoW * scale));
        int vh = Math.max(1, Math.round(videoH * scale));
        ViewGroup.LayoutParams raw = tv.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (lp.width != vw || lp.height != vh || lp.gravity != android.view.Gravity.CENTER) {
                lp.width = vw;
                lp.height = vh;
                lp.gravity = android.view.Gravity.CENTER;
                tv.setLayoutParams(lp);
            }
        } else if (raw != null) {
            raw.width = vw;
            raw.height = vh;
            tv.setLayoutParams(raw);
        }
        tv.setPivotX(vw / 2f);
        tv.setPivotY(vh / 2f);
        tv.setRotation(rot);
        tv.setScaleX(mirror ? -1f : 1f);
        tv.setScaleY(1f);
        tv.setVisibility(View.VISIBLE);
    }

    public static String networkLabel(Context context) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities caps =
                    cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) return "无网络";
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动网络";
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "以太网";
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        } catch (Exception ignored) {
        }
        return "未知";
    }

    public static void requirePin(Activity activity, Prefs prefs, Runnable onSuccess) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_pin, null);
        EditText input = view.findViewById(R.id.pinInput);
        TextView hint = view.findViewById(R.id.pinHint);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.pin_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String pin = input.getText().toString();
                if (pin.equals(prefs.getParentPin())) {
                    dialog.dismiss();
                    onSuccess.run();
                } else {
                    hint.setText(R.string.pin_wrong);
                    hint.setTextColor(activity.getColor(R.color.danger));
                    input.setText("");
                }
            });
        });
        dialog.show();
        input.requestFocus();
    }

    public static void setText(TextView tv, String text) {
        if (tv == null) return;
        tv.setText(text == null ? "" : text);
    }

    public static int mixColor(int color, float alpha) {
        return Color.argb((int) (255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }
}
