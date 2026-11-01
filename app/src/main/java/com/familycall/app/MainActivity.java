package com.familycall.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.familycall.app.data.CallInfo;
import com.familycall.app.data.CallSnapshot;
import com.familycall.app.data.Contact;
import com.familycall.app.data.Prefs;
import com.familycall.app.net.Protocol;
import com.familycall.app.ui.UiUtils;

import java.util.List;

public class MainActivity extends Activity implements CallService.UiListener {
    private static final int REQ_PERMISSIONS = 100;
    private Prefs prefs;
    private CallService service;
    private boolean bound;
    private boolean permissionDialogShown;
    private boolean fsiDialogShown;

    private TextView appTitle;
    private TextView subtitle;
    private TextView statusText;
    private View statusDot;
    private LinearLayout statusPill;
    private ImageButton settingsButton;
    private ImageButton parentButton;
    private LinearLayout childBanner;
    private LinearLayout hero;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private TextView heroAddress;
    private TextView addContact;
    private LinearLayout contactList;
    private LinearLayout emptyContacts;
    private TextView emptyText;
    private LinearLayout directCard;
    private TextView localInfo;
    private TextView childHint;
    private TextView alertBanner;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CallService.LocalBinder) binder).getService();
            bound = true;
            service.refreshForegroundTypes();
            service.addUiListener(MainActivity.this);
            renderAll(null);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            renderServiceStatus(null);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        bindViews();
        bindActions();
        ensurePermissions();
        startAndBindService();
        updateAlerts();
        maybePromptFullScreenIntent();
    }

    private void bindViews() {
        appTitle = findViewById(R.id.appTitle);
        subtitle = findViewById(R.id.subtitle);
        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        statusPill = findViewById(R.id.statusPill);
        settingsButton = findViewById(R.id.settingsButton);
        parentButton = findViewById(R.id.parentButton);
        childBanner = findViewById(R.id.childBanner);
        hero = findViewById(R.id.hero);
        heroTitle = findViewById(R.id.heroTitle);
        heroSubtitle = findViewById(R.id.heroSubtitle);
        heroAddress = findViewById(R.id.heroAddress);
        addContact = findViewById(R.id.addContact);
        contactList = findViewById(R.id.contactList);
        emptyContacts = findViewById(R.id.emptyContacts);
        emptyText = findViewById(R.id.emptyText);
        directCard = findViewById(R.id.directCard);
        localInfo = findViewById(R.id.localInfo);
        childHint = findViewById(R.id.childHint);
        alertBanner = findViewById(R.id.alertBanner);
        alertBanner.setOnClickListener(v -> {
            if (!UiUtils.notificationsEnabled(this)) {
                UiUtils.openNotificationSettings(this);
            } else if (UiUtils.isMiuiFamily() && !UiUtils.miuiLockscreenAllowed(this)) {
                UiUtils.openMiuiPermissionEditor(this);
            } else {
                UiUtils.openFullScreenIntentSettings(this);
            }
        });
    }

    private void bindActions() {
        settingsButton.setOnClickListener(v -> openSettingsWithPin());
        parentButton.setOnClickListener(v -> openSettingsWithPin());
        appTitle.setOnLongClickListener(v -> {
            openSettingsWithPin();
            return true;
        });
        addContact.setOnClickListener(v -> showContactEditor(null));
        statusPill.setOnClickListener(v -> {
            if (service != null) {
                CallSnapshot s = service.getSnapshot();
                if (s.stage == CallSnapshot.Stage.RINGING) {
                    startActivity(new Intent(this, IncomingCallActivity.class));
                } else if (s.isBusy()) {
                    startActivity(new Intent(this, CallActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                }
            }
        });
    }

    private void openSettingsWithPin() {
        if (prefs.isChildMode()) {
            UiUtils.requirePin(this, prefs, () ->
                    startActivity(new Intent(this, SettingsActivity.class)));
        } else {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, CallService.class).setAction(CallService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            UiUtils.toastLong(this, "无法启动通话服务：" + e.getMessage());
        }
        bindService(new Intent(this, CallService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private void maybePromptFullScreenIntent() {
        if (Build.VERSION.SDK_INT < 34) return;
        if (UiUtils.canUseFullScreenIntent(this)) return;
        if (fsiDialogShown) return;
        fsiDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.fsi_title)
                .setMessage(R.string.fsi_message)
                .setPositiveButton(R.string.fsi_allow,
                        (d, w) -> UiUtils.openFullScreenIntentSettings(this))
                .setNegativeButton(R.string.fsi_later, null)
                .show();
    }

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        boolean camera = hasPermission(Manifest.permission.CAMERA);
        boolean mic = hasPermission(Manifest.permission.RECORD_AUDIO);
        if (service != null) service.refreshForegroundTypes();
        if (!camera || !mic) {
            showPermissionDialog();
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            UiUtils.toastLong(this, getString(R.string.notification_permission));
        }
        updateAlerts();
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionDialog() {
        if (permissionDialogShown) return;
        permissionDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.permission_message)
                .setPositiveButton(R.string.open_settings, (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateAlerts() {
        if (alertBanner == null) return;
        android.util.Log.i("MainActivity", "alerts miui=" + UiUtils.isMiuiFamily()
                + " miuiLock=" + UiUtils.miuiLockscreenAllowed(this)
                + " fsi=" + UiUtils.canUseFullScreenIntent(this)
                + " notif=" + UiUtils.notificationsEnabled(this));
        if (!UiUtils.notificationsEnabled(this)) {
            alertBanner.setText(R.string.alert_notifications);
            alertBanner.setVisibility(View.VISIBLE);
        } else if (UiUtils.isMiuiFamily() && !UiUtils.miuiLockscreenAllowed(this)) {
            alertBanner.setText(R.string.alert_miui);
            alertBanner.setVisibility(View.VISIBLE);
        } else if (Build.VERSION.SDK_INT >= 34 && !UiUtils.canUseFullScreenIntent(this)) {
            alertBanner.setText(R.string.alert_fsi);
            alertBanner.setVisibility(View.VISIBLE);
        } else {
            alertBanner.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs = new Prefs(this);
        updateAlerts();
        renderAll(null);
        if (bound && service != null) {
            renderServiceStatus(service.getSnapshot());
        }
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            if (service != null) service.removeUiListener(this);
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        super.onDestroy();
    }

    private void renderAll(CallSnapshot snapshot) {
        boolean child = prefs.isChildMode();
        appTitle.setText(R.string.app_name);
        subtitle.setText(child ? R.string.home_subtitle_child : R.string.home_subtitle);
        settingsButton.setVisibility(child ? View.GONE : View.VISIBLE);
        parentButton.setVisibility(child ? View.VISIBLE : View.GONE);
        addContact.setVisibility(child ? View.GONE : View.VISIBLE);
        childBanner.setVisibility(child ? View.VISIBLE : View.GONE);
        hero.setVisibility(child ? View.GONE : View.VISIBLE);
        directCard.setVisibility(child ? View.GONE : View.VISIBLE);
        childHint.setVisibility(child ? View.VISIBLE : View.GONE);
        emptyText.setText(child ? R.string.empty_contacts_child : R.string.empty_contacts);

        if (!child) {
            heroTitle.setText("和家人，随时见一面");
            heroSubtitle.setText("点对点直连 · 无需账号 · 移动网络自适应");
            String host = Protocol.localIpv4();
            String addr = "本机 " + prefs.getName() + "  ·  "
                    + (host.startsWith("[") ? host : host + ":" + prefs.getLocalPort());
            if (host.startsWith("[")) addr += ":" + prefs.getLocalPort();
            heroAddress.setText(addr);
        }
        localInfo.setText("名字  " + prefs.getName()
                + "\n地址  " + Protocol.localIpv4Detailed()
                + "\n端口  " + prefs.getLocalPort() + "  (TCP + UDP)");

        renderContacts(child);
        renderServiceStatus(snapshot);
    }

    private void renderContacts(boolean child) {
        contactList.removeAllViews();
        List<Contact> contacts = prefs.getContacts();
        emptyContacts.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Contact c : contacts) {
            View row = inflater.inflate(R.layout.item_contact, contactList, false);
            TextView avatar = row.findViewById(R.id.avatar);
            TextView name = row.findViewById(R.id.name);
            TextView endpoint = row.findViewById(R.id.endpoint);
            ImageButton edit = row.findViewById(R.id.editButton);
            ImageButton call = row.findViewById(R.id.callButton);
            avatar.setText(c.initials());
            name.setText(c.name);
            endpoint.setText(child ? "点击拨打视频电话" : c.endpoint());
            edit.setVisibility(child ? View.GONE : View.VISIBLE);
            edit.setOnClickListener(v -> showContactEditor(c));
            View.OnClickListener dial = v -> dial(c);
            call.setOnClickListener(dial);
            row.setOnClickListener(dial);
            if (!child) {
                row.setOnLongClickListener(v -> {
                    showContactEditor(c);
                    return true;
                });
            }
            contactList.addView(row);
        }
    }

    private void dial(Contact contact) {
        if (prefs.isChildMode() && prefs.getContacts().isEmpty()) {
            UiUtils.toastLong(this, getString(R.string.child_no_contacts));
            return;
        }
        if (!hasPermission(Manifest.permission.CAMERA)
                || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            showPermissionDialog();
            ensurePermissions();
            return;
        }
        if (service == null) {
            UiUtils.toast(this, "通话服务正在启动，请稍后再试");
            return;
        }
        service.callContact(contact);
    }

    private void renderServiceStatus(CallSnapshot snapshot) {
        boolean listening = service != null && service.isListening();
        CallSnapshot s = snapshot != null ? snapshot
                : (service != null ? service.getSnapshot() : null);
        if (s != null && s.isBusy()) {
            statusPill.setBackgroundResource(R.drawable.bg_pill_amber);
            statusDot.setBackgroundResource(R.drawable.bg_dot_amber);
            statusText.setTextColor(getColor(R.color.ink));
            String name = s.peerName == null || s.peerName.isEmpty() ? "对方" : s.peerName;
            statusText.setText("通话进行中 · " + name + "  ·  点击返回");
            return;
        }
        if (listening) {
            statusPill.setBackgroundResource(R.drawable.bg_pill_green);
            statusDot.setBackgroundResource(R.drawable.bg_dot_green);
            statusText.setTextColor(getColor(R.color.green_dark));
            statusText.setText(getString(R.string.listening, service.getListeningPort()));
        } else {
            statusPill.setBackgroundResource(R.drawable.bg_pill_amber);
            statusDot.setBackgroundResource(R.drawable.bg_dot_red);
            statusText.setTextColor(getColor(R.color.ink));
            String err = service == null ? "服务启动中…" : service.getListenerError();
            statusText.setText(err == null ? getString(R.string.not_listening) : err);
        }
    }

    private void showContactEditor(Contact existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_contact, null);
        EditText nameInput = view.findViewById(R.id.nameInput);
        EditText hostInput = view.findViewById(R.id.hostInput);
        EditText portInput = view.findViewById(R.id.portInput);
        TextView error = view.findViewById(R.id.errorText);

        if (existing != null) {
            nameInput.setText(existing.name);
            hostInput.setText(existing.host);
            portInput.setText(String.valueOf(existing.port));
        } else {
            portInput.setText(String.valueOf(prefs.getLocalPort()));
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.new_contact : R.string.edit_contact)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        if (existing != null) {
            b.setNeutralButton(R.string.delete, null);
        }
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = nameInput.getText().toString().trim();
                String host = hostInput.getText().toString().trim();
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }
                int port;
                try {
                    port = Integer.parseInt(portInput.getText().toString().trim());
                } catch (Exception e) {
                    port = 0;
                }
                if (name.isEmpty()) {
                    showDialogError(error, getString(R.string.name_required));
                    return;
                }
                if (host.isEmpty() || host.contains(" ")) {
                    showDialogError(error, getString(R.string.contact_invalid));
                    return;
                }
                if (port < 1 || port > 65535) {
                    showDialogError(error, getString(R.string.port_invalid));
                    return;
                }
                Contact c = new Contact(existing == null ? null : existing.id, name, host, port);
                prefs.addOrUpdateContact(c);
                dialog.dismiss();
                renderAll(null);
            });
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(getColor(R.color.danger));
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.delete)
                                .setMessage("删除联系人“" + existing.name + "”？")
                                .setPositiveButton(R.string.delete, (dd, w) -> {
                                    prefs.deleteContact(existing.id);
                                    dialog.dismiss();
                                    renderAll(null);
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show());
            }
        });
        dialog.show();
    }

    private void showDialogError(TextView error, String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSnapshot(CallSnapshot snapshot) {
        renderServiceStatus(snapshot);
    }

    @Override
    public void onIncoming(CallInfo info) {
        // IncomingCallActivity is launched by the service.
    }

    @Override
    public void onIncomingCancelled() {
    }

    @Override
    public void onCallEnded(String message) {
        UiUtils.toast(this, message);
    }

    @Override
    public void onServiceMessage(String message) {
        UiUtils.toastLong(this, message);
    }
}
