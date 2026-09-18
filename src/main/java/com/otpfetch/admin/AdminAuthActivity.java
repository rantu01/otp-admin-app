package com.otpfetch.admin;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
        EditText server = findViewById(R.id.serverInput);
        EditText login = findViewById(R.id.loginInput);
        EditText pass = findViewById(R.id.passInput);
        TextView hint = findViewById(R.id.authHint);
        Button btn = findViewById(R.id.loginBtn);
        server.setText(AdminSession.getBase(this));
        btn.setOnClickListener(v -> {
            String base = server.getText().toString().trim();
            String l = login.getText().toString().trim();
            String p = pass.getText().toString();
            if (base.isEmpty() || l.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "All fields required", Toast.LENGTH_SHORT).show();
                return;
            }
            AdminSession.setBase(this, base);
            hint.setText("Logging in...");
            net.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("login", l);
                    body.put("password", p);
                    AdminApi.Resp r = AdminApi.post(this, "/api/auth/login", body);
                    if (!r.ok()) throw new Exception(r.json.optString("error", "Login failed"));
                    JSONObject user = r.json.optJSONObject("user");
                    if (user == null || !"admin".equals(user.optString("role"))) {
                        runOnUiThread(() -> hint.setText("Not an admin account."));
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
                        startActivity(new Intent(this, AdminMainActivity.class));
                        finish();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> hint.setText("Error: " + e.getMessage()));
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
