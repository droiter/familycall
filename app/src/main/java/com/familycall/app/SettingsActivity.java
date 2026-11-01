package com.familycall.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import com.familycall.app.data.CallSnapshot;
import com.familycall.app.data.Prefs;
import com.familycall.app.net.Protocol;
import com.familycall.app.ui.UiUtils;

public class SettingsActivity extends Activity {
    private Prefs prefs;
    private CallService service;
    private boolean bound;

    private EditText nameInput;
    private EditText portInput;
    private EditText pinInput;
    private Switch childSwitch;
    private RadioButton qualityAuto;
    private RadioButton qualitySmooth;
    private RadioButton qualityHd;
    private TextView addressText;
    private TextView serviceText;
    private TextView networkText;
    private LinearLayout fsiCard;
    private TextView fsiStatus;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CallService.LocalBinder) binder).getService();
            bound = true;
            renderService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            renderService();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        nameInput = findViewById(R.id.nameInput);
        portInput = findViewById(R.id.portInput);
        pinInput = findViewById(R.id.pinInput);
        childSwitch = findViewById(R.id.childSwitch);
        qualityAuto = findViewById(R.id.qualityAuto);
        qualitySmooth = findViewById(R.id.qualitySmooth);
        qualityHd = findViewById(R.id.qualityHd);
        addressText = findViewById(R.id.addressText);
        serviceText = findViewById(R.id.serviceText);
        networkText = findViewById(R.id.networkText);
        fsiCard = findViewById(R.id.fsiCard);
        fsiStatus = findViewById(R.id.fsiStatus);
        ((TextView) findViewById(R.id.versionText)).setText("亲情视频 v" + BuildConfig.VERSION_NAME);
        findViewById(R.id.fsiButton).setOnClickListener(v -> UiUtils.openFullScreenIntentSettings(this));

        nameInput.setText(prefs.getName());
        portInput.setText(String.valueOf(prefs.getLocalPort()));
        pinInput.setText(prefs.getParentPin());
        childSwitch.setChecked(prefs.isChildMode());
        int q = prefs.getQuality();
        qualityAuto.setChecked(q == Prefs.QUALITY_AUTO);
        qualitySmooth.setChecked(q == Prefs.QUALITY_SMOOTH);
        qualityHd.setChecked(q == Prefs.QUALITY_HD);
        if (!qualityAuto.isChecked() && !qualitySmooth.isChecked() && !qualityHd.isChecked()) {
            qualityAuto.setChecked(true);
        }
        renderAddress();
        renderFsi();

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.saveButton).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.restartButton).setOnClickListener(v -> restartListener());
        findViewById(R.id.stopButton).setOnClickListener(v -> confirmStop());
        childSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && pinInput.getText().toString().trim().isEmpty()) {
                pinInput.setText(prefs.getParentPin());
            }
        });

        bindService(new Intent(this, CallService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private void renderFsi() {
        if (Build.VERSION.SDK_INT < 34) {
            fsiCard.setVisibility(View.GONE);
            return;
        }
        fsiCard.setVisibility(View.VISIBLE);
        boolean allowed = UiUtils.canUseFullScreenIntent(this);
        fsiStatus.setText(allowed ? R.string.fsi_enabled : R.string.fsi_disabled);
        fsiStatus.setTextColor(getColor(allowed ? R.color.green_dark : R.color.danger));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderFsi();
    }

    private void renderAddress() {
        int port = prefs.getLocalPort();
        try {
            String p = portInput.getText().toString().trim();
            if (!p.isEmpty()) port = Integer.parseInt(p);
        } catch (Exception ignored) {
        }
        addressText.setText("本机地址  " + Protocol.localIpv4() + ":" + port
                + "\n请将此地址和端口告诉家人");
    }

    private void renderService() {
        if (service == null) {
            serviceText.setText("通话服务未连接");
            networkText.setText("本机网络：" + UiUtils.networkLabel(this));
            return;
        }
        serviceText.setText(service.isListening()
                ? "正在监听 · 端口 " + service.getListeningPort()
                : (service.getListenerError() == null ? "服务未监听" : service.getListenerError()));
        networkText.setText("本机网络：" + UiUtils.networkLabel(this)
                + "\n本机地址：\n" + Protocol.localIpv4Detailed());
        renderAddress();
    }

    private void saveAndFinish() {
        if (service != null && service.isBusy()) {
            UiUtils.toastLong(this, "通话进行中，请先结束通话再修改设置");
            return;
        }
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            UiUtils.toast(this, getString(R.string.name_required));
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (Exception e) {
            port = 0;
        }
        if (port < 1 || port > 65535) {
            UiUtils.toast(this, getString(R.string.port_invalid));
            return;
        }
        String pin = pinInput.getText().toString().trim();
        if (childSwitch.isChecked()) {
            if (!pin.matches("\\d{4,8}")) {
                UiUtils.toast(this, "儿童模式需要 4-8 位数字家长密码");
                return;
            }
        } else if (!pin.isEmpty() && !pin.matches("\\d{4,8}")) {
            UiUtils.toast(this, "家长密码需为 4-8 位数字");
            return;
        }
        prefs.setName(name);
        prefs.setLocalPort(port);
        prefs.setQuality(qualityHd.isChecked() ? Prefs.QUALITY_HD
                : qualitySmooth.isChecked() ? Prefs.QUALITY_SMOOTH : Prefs.QUALITY_AUTO);
        prefs.setChildMode(childSwitch.isChecked());
        if (!pin.isEmpty()) prefs.setParentPin(pin);
        if (service != null) service.applySettingsChanged();
        UiUtils.toast(this, getString(R.string.settings_saved));
        if (childSwitch.isChecked() && prefs.getContacts().isEmpty()) {
            UiUtils.toastLong(this, "儿童模式已开启，但还没有联系人。请先添加联系人。");
        }
        finish();
    }

    private void restartListener() {
        if (service != null && service.isBusy()) {
            UiUtils.toastLong(this, "通话进行中，请先结束通话再修改端口");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (Exception e) {
            port = 0;
        }
        if (port < 1 || port > 65535) {
            UiUtils.toast(this, getString(R.string.port_invalid));
            return;
        }
        prefs.setLocalPort(port);
        if (service != null) {
            service.applySettingsChanged();
            UiUtils.toast(this, "已按新端口重新监听");
            renderService();
        } else {
            UiUtils.toast(this, "通话服务未连接");
        }
    }

    private void confirmStop() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stop_service)
                .setMessage("停止后应用将无法接收视频来电，直到再次打开。确定停止吗？")
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    if (service != null) service.requestStop();
                    finishAffinity();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        super.onDestroy();
    }
}
