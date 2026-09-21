package com.otpfetch.admin;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Admin login. Refuses non-admin accounts (backend role is also enforced). */
public class AdminAuthActivity extends AppCompatActivity {

    private final ExecutorService net = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_auth);
        if (AdminSession.isLoggedIn(this)) {
            startActivity(new Intent(this, AdminMainActivity.class));
            finish();
            return;
        }
        EditText login = findViewById(R.id.loginInput);
        EditText pass = findViewById(R.id.passInput);
        TextView hint = findViewById(R.id.authHint);
        Button btn = findViewById(R.id.loginBtn);
        // Backend URL is centralized (ApiConfig) — never entered manually.
        AdminSession.setBase(this, ApiConfig.DEFAULT_BASE_URL);
        btn.setOnClickListener(v -> {
            String l = login.getText().toString().trim();
            String p = pass.getText().toString();
            if (l.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "Email and password required", Toast.LENGTH_SHORT).show();
                return;
            }
            hint.setText("Logging in...");
            hint.setTextColor(ContextCompat.getColor(this, R.color.muted_text));
            UiBusy.setBusy(btn, "Logging in...");
            net.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("login", l);
                    body.put("password", p);
                    try {
                        String devId = android.provider.Settings.Secure.getString(
                                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                        if (devId != null && !devId.isEmpty()) body.put("deviceId", devId);
                    } catch (Exception ignored) {}
                    AdminApi.Resp r = AdminApi.post(this, "/api/auth/login", body);
                    if (!r.ok()) throw new Exception(r.json.optString("error", "Login failed"));
                    JSONObject user = r.json.optJSONObject("user");
                    if (user == null || !"admin".equals(user.optString("role"))) {
                        runOnUiThread(() -> {
                            UiBusy.setIdle(btn);
                            hint.setText("Not an admin account.");
                            hint.setTextColor(ContextCompat.getColor(this, R.color.danger));
                        });
                        return;
                    }
                    AdminSession.save(this, r.json.optString("token", ""), user);
                    // Register a device token so the backend can push new-payment alerts
                    // (falls back to polling when FCM is not configured).
                    try {
                        String devId = android.provider.Settings.Secure.getString(
                                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                        JSONObject t = new JSONObject();
                        t.put("token", "dev-" + devId);
                        t.put("platform", "android");
                        AdminApi.post(this, "/api/admin/fcm-tokens", t);
                    } catch (Exception ignored) {}
                    runOnUiThread(() -> {
                        UiBusy.setIdle(btn);
                        startActivity(new Intent(this, AdminMainActivity.class));
                        finish();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        UiBusy.setIdle(btn);
                        hint.setText("Error: " + e.getMessage());
                        hint.setTextColor(ContextCompat.getColor(this, R.color.danger));
                    });
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
