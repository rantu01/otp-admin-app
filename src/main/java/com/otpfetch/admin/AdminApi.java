package com.otpfetch.admin;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Minimal admin REST client (HttpURLConnection + org.json, no new deps). */
public final class AdminApi {
    private AdminApi() {}

    public static final class Resp {
        public final int code;
        public final JSONObject json;
        Resp(int c, JSONObject j) { code = c; json = j; }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    private static Resp call(Context ctx, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(AdminSession.getBase(ctx) + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String t = AdminSession.getToken(ctx);
        if (t != null && !t.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + t);
        if (body != null) {
            conn.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
        }
        conn.disconnect();
        JSONObject json;
        try {
            json = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        } catch (Exception e) {
            json = new JSONObject();
            json.put("_raw", sb.toString());
        }
        return new Resp(code, json);
    }

    public static Resp get(Context ctx, String path) throws Exception {
        return call(ctx, "GET", path, null);
    }

    public static Resp post(Context ctx, String path, JSONObject body) throws Exception {
        return call(ctx, "POST", path, body == null ? new JSONObject() : body);
    }

    public static Resp put(Context ctx, String path, JSONObject body) throws Exception {
        return call(ctx, "PUT", path, body == null ? new JSONObject() : body);
    }

    public static Resp patch(Context ctx, String path, JSONObject body) throws Exception {
        return call(ctx, "PATCH", path, body == null ? new JSONObject() : body);
    }

    public static Resp delete(Context ctx, String path) throws Exception {
        return call(ctx, "DELETE", path, null);
    }

    public static String qs(String... kv) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Multipart upload: sends APK file + JSON fields to the given path. */
    public static Resp uploadApk(Context ctx, String path, String filePath, String fieldName,
                                 String versionName, String versionCode, String releaseNotes, String updateRequired) throws Exception {
        String boundary = "----OtapkBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(AdminSession.getBase(ctx) + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        String t = AdminSession.getToken(ctx);
        if (t != null && !t.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + t);

        DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
        // JSON fields
        String[] fields = { "versionName", versionName, "versionCode", versionCode, "releaseNotes", releaseNotes, "updateRequired", updateRequired };
        for (int i = 0; i < fields.length; i += 2) {
            if (fields[i + 1] == null || fields[i + 1].isEmpty()) continue;
            dos.writeBytes("--" + boundary + "\r\n");
            dos.writeBytes("Content-Disposition: form-data; name=\"" + fields[i] + "\"\r\n\r\n");
            dos.writeBytes(fields[i + 1] + "\r\n");
        }
        // File
        java.io.File file = new java.io.File(filePath);
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + file.getName() + "\"\r\n");
        dos.writeBytes("Content-Type: application/vnd.android.package-archive\r\n\r\n");
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = fis.read(buffer)) != -1) {
            dos.write(buffer, 0, len);
        }
        fis.close();
        dos.writeBytes("\r\n");
        dos.writeBytes("--" + boundary + "--\r\n");
        dos.flush();
        dos.close();

        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
        }
        conn.disconnect();
        JSONObject json;
        try {
            json = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        } catch (Exception e) {
            json = new JSONObject();
            json.put("_raw", sb.toString());
        }
        return new Resp(code, json);
    }
}
