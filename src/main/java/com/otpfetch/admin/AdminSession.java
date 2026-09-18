package com.otpfetch.admin;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Admin session: token + backend base URL. Only role=admin may log in here. */
public final class AdminSession {
    private static final String PREFS = "otp_admin_prefs";
    public static final String DEFAULT_BASE = "http://10.0.2.2:4000";

    private AdminSession() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getBase(Context ctx) {
        String v = prefs(ctx).getString("base", DEFAULT_BASE);
        return (v == null || v.isEmpty()) ? DEFAULT_BASE : v.replaceAll("/+$", "");
    }

    public static void setBase(Context ctx, String url) {
        prefs(ctx).edit().putString("base", url == null ? DEFAULT_BASE : url.trim().replaceAll("/+$", "")).apply();
    }

    public static String getToken(Context ctx) {
        return prefs(ctx).getString("token", "");
    }

    public static boolean isLoggedIn(Context ctx) {
        String t = getToken(ctx);
        return t != null && !t.isEmpty();
    }

    public static void save(Context ctx, String token, JSONObject admin) {
        prefs(ctx).edit().putString("token", token == null ? "" : token)
                .putString("admin", admin == null ? "" : admin.toString()).apply();
    }

    public static void logout(Context ctx) {
        prefs(ctx).edit().remove("token").remove("admin").apply();
    }
}
