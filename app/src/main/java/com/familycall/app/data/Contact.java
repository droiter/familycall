package com.familycall.app.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.UUID;

/** A directly reachable peer: display name, host/IP and listening port. */
public final class Contact {
    public final String id;
    public final String name;
    public final String host;
    public final int port;

    public Contact(String id, String name, String host, int port) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.name = name == null ? "" : name.trim();
        String h = host == null ? "" : host.trim();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        this.host = h;
        this.port = port;
    }

    public Contact(String name, String host, int port) {
        this(null, name, host, port);
    }

    public boolean isValid() {
        return !name.isEmpty() && !host.isEmpty() && port > 0 && port <= 65535;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("host", host);
            o.put("port", port);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Contact fromJson(JSONObject o) {
        if (o == null) return null;
        return new Contact(o.optString("id", null), o.optString("name", ""),
                o.optString("host", ""), o.optInt("port", 0));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Contact)) return false;
        Contact c = (Contact) obj;
        return port == c.port && id.equals(c.id) && name.equals(c.name) && host.equals(c.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, host, port);
    }

    public String endpoint() {
        return host + ":" + port;
    }

    public String initials() {
        if (name.isEmpty()) return "?";
        return name.substring(0, Math.min(2, name.length()));
    }
}
