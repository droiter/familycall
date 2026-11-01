package com.familycall.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Single source of truth for user configuration. */
public final class Prefs {
    private static final String FILE = "family_call_prefs";
    private static final String KEY_NAME = "name";
    private static final String KEY_PORT = "local_port";
    private static final String KEY_CHILD = "child_mode";
    private static final String KEY_PIN = "parent_pin";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_CONTACTS = "contacts";
    private static final String KEY_SPEAKER = "speaker";
    private static final String KEY_PREVIEW_OFFSET = "preview_offset";

    public static final int QUALITY_AUTO = 0;
    public static final int QUALITY_SMOOTH = 1;
    public static final int QUALITY_HD = 2;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getName() {
        return sp.getString(KEY_NAME, "我的手机");
    }

    public void setName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "我的手机";
        sp.edit().putString(KEY_NAME, clean).apply();
    }

    public int getLocalPort() {
        int p = sp.getInt(KEY_PORT, 50000);
        return p >= 1 && p <= 65535 ? p : 50000;
    }

    public void setLocalPort(int port) {
        sp.edit().putInt(KEY_PORT, port).apply();
    }

    public boolean isChildMode() {
        return sp.getBoolean(KEY_CHILD, false);
    }

    public void setChildMode(boolean child) {
        sp.edit().putBoolean(KEY_CHILD, child).apply();
    }

    public String getParentPin() {
        String pin = sp.getString(KEY_PIN, "1234");
        return pin == null || pin.length() < 4 ? "1234" : pin;
    }

    public void setParentPin(String pin) {
        if (pin != null && pin.matches("\\d{4,8}")) {
            sp.edit().putString(KEY_PIN, pin).apply();
        }
    }

    public int getQuality() {
        return sp.getInt(KEY_QUALITY, QUALITY_AUTO);
    }

    public void setQuality(int quality) {
        sp.edit().putInt(KEY_QUALITY, quality).apply();
    }

    public boolean isSpeakerOn() {
        return sp.getBoolean(KEY_SPEAKER, true);
    }

    public void setSpeakerOn(boolean on) {
        sp.edit().putBoolean(KEY_SPEAKER, on).apply();
    }

    /** -1 means automatic; otherwise an explicit 0/90/180/270 correction. */
    public int getPreviewOffset() {
        return sp.getInt(KEY_PREVIEW_OFFSET, -1);
    }

    public void setPreviewOffset(int degrees) {
        sp.edit().putInt(KEY_PREVIEW_OFFSET, ((degrees % 360) + 360) % 360).apply();
    }

    public List<Contact> getContacts() {
        String raw = sp.getString(KEY_CONTACTS, "[]");
        List<Contact> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Contact c = Contact.fromJson(arr.optJSONObject(i));
                if (c != null && c.isValid()) result.add(c);
            }
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableList(result);
    }

    public void saveContacts(List<Contact> contacts) {
        JSONArray arr = new JSONArray();
        for (Contact c : contacts) {
            if (c != null && c.isValid()) arr.put(c.toJson());
        }
        sp.edit().putString(KEY_CONTACTS, arr.toString()).apply();
    }

    public void addOrUpdateContact(Contact contact) {
        List<Contact> list = new ArrayList<>(getContacts());
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(contact.id)) {
                list.set(i, contact);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(contact);
        saveContacts(list);
    }

    public void deleteContact(String id) {
        List<Contact> list = new ArrayList<>(getContacts());
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        saveContacts(list);
    }

    public Contact findContact(String id) {
        for (Contact c : getContacts()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    public boolean isApprovedContact(Contact contact) {
        if (contact == null) return false;
        for (Contact c : getContacts()) {
            if (c.id.equals(contact.id) && c.host.equals(contact.host) && c.port == contact.port) {
                return true;
            }
        }
        return false;
    }
}
