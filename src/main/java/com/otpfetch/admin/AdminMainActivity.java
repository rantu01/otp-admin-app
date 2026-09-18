package com.otpfetch.admin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Admin console: dashboard, payment verification (copy TxID / approve / reject),
 * users & access, packages, payment methods, app versions.
 * Shares the same backend as the Admin Website (re-fetch to sync).
 */
public class AdminMainActivity extends AppCompatActivity {

    private static final String CH = "admin_payments";
    private static final long POLL_MS = 30_000;

    private final ExecutorService net = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler pollHandler = new Handler(Looper.getMainLooper());

    private TextView headerView, pendingBadge;
    private LinearLayout content;
    private String tab = "dashboard";
    private int lastPending = 0;
    private JSONArray cachePackages = new JSONArray();
    private final List<Button> navButtons = new ArrayList<>();

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            net.execute(() -> {
                try {
                    AdminApi.Resp r = AdminApi.get(AdminMainActivity.this, "/api/admin/payments/pending-count");
                    if (r.ok()) {
                        int n = r.json.optInt("pending", 0);
                        main.post(() -> onPendingCount(n));
                    }
                } catch (Exception ignored) {}
                pollHandler.postDelayed(this, POLL_MS);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AdminSession.isLoggedIn(this)) {
            startActivity(new Intent(this, AdminAuthActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_admin_main);
        headerView = findViewById(R.id.headerView);
        pendingBadge = findViewById(R.id.pendingBadge);
        content = findViewById(R.id.content);
        Button refreshBtn = findViewById(R.id.refreshBtn);
        Button logoutBtn = findViewById(R.id.logoutBtn);
        LinearLayout nav = findViewById(R.id.navRow);
        String[] tabs = {"dashboard", "payments", "users", "packages", "methods", "versions"};
        for (String t : tabs) {
            Button b = new Button(this);
            b.setText(t.toUpperCase());
            b.setTextSize(11);
            b.setTag(t);
            b.setOnClickListener(v -> {
                tab = t;
                refreshNav();
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            b.setLayoutParams(lp);
            navButtons.add(b);
            nav.addView(b);
        }
        refreshNav();
        refreshBtn.setOnClickListener(v -> render());
        logoutBtn.setOnClickListener(v -> {
            AdminSession.logout(this);
            startActivity(new Intent(this, AdminAuthActivity.class));
            finish();
        });
        ensureChannel();
        render();
        pollHandler.post(poller);
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacks(poller);
        net.shutdownNow();
        super.onDestroy();
    }

    // ---------------- pending badge + background notification ----------------
    private void onPendingCount(int n) {
        pendingBadge.setText("Pending: " + n);
        if (n > lastPending && lastPending >= 0 && n > 0) {
            notifyNewPayment(n);
        }
        lastPending = n;
    }

    private void ensureChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(
                        new NotificationChannel(CH, "Admin payments", NotificationManager.IMPORTANCE_HIGH));
            }
        } catch (Exception ignored) {}
    }

    /** Local alert when a new payment arrives (works in background while process lives; enable FCM key server-side for killed-app push). */
    private void notifyNewPayment(int pending) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                    .setContentTitle("New payment received for verification")
                    .setContentText(pending + " pending payment(s) — tap to review")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(9001, b.build());
            Toast.makeText(this, "New payment received for verification", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    // ---------------- rendering ----------------
    private void render() {
        content.removeAllViews();
        headerView.setText("Admin · " + tab.toUpperCase() + " · " + AdminSession.getBase(this));
        TextView loading = new TextView(this);
        loading.setText("Loading...");
        content.addView(loading);
        switch (tab) {
            case "payments": renderPayments("PENDING", ""); break;
            case "users": renderUsers(""); break;
            case "packages": renderPackages(); break;
            case "methods": renderMethods(); break;
            case "versions": renderVersions(); break;
            default: renderDashboard(); break;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** Theme-aware card: uses @drawable/bg_card so light/dark both keep contrast. */
    private void styleCard(LinearLayout card) {
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_card));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
    }

    private TextView tv(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_card));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        t.setLayoutParams(lp);
        return t;
    }

    private Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        return b;
    }

    /** Active tab = filled primary, inactive = card surface with primary text. */
    private void refreshNav() {
        for (Button b : navButtons) {
            boolean active = tab.equals(b.getTag());
            if (active) {
                b.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.tab_active));
                b.setTextColor(ContextCompat.getColor(this, R.color.on_tint));
            } else {
                b.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.card_bg));
                b.setTextColor(ContextCompat.getColor(this, R.color.tab_active));
            }
        }
    }

    private void styleApprove(Button b) {
        b.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.success));
        b.setTextColor(ContextCompat.getColor(this, R.color.on_tint));
    }

    private void styleReject(Button b) {
        b.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.danger));
        b.setTextColor(ContextCompat.getColor(this, R.color.on_tint));
    }

    private void styleNeutral(Button b) {
        b.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.info_bg));
        b.setTextColor(ContextCompat.getColor(this, R.color.title_text));
    }

    /** Small rounded status pill (PENDING amber / APPROVED green / REJECTED red). */
    private TextView statusPill(String status) {
        TextView p = new TextView(this);
        p.setText(status == null || status.isEmpty() ? "—" : status);
        p.setTextSize(11);
        p.setTypeface(null, android.graphics.Typeface.BOLD);
        p.setPadding(dp(10), dp(5), dp(10), dp(5));
        int bg, fg;
        if ("APPROVED".equals(status) || "active".equals(status) || "ON".equals(status)) {
            bg = R.color.pill_ok_bg; fg = R.color.pill_ok_text;
        } else if ("REJECTED".equals(status) || "inactive".equals(status) || "OFF".equals(status)) {
            bg = R.color.pill_bad_bg; fg = R.color.pill_bad_text;
        } else {
            bg = R.color.pill_pending_bg; fg = R.color.pill_pending_text;
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(50));
        d.setColor(ContextCompat.getColor(this, bg));
        p.setBackground(d);
        p.setTextColor(ContextCompat.getColor(this, fg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        p.setLayoutParams(lp);
        return p;
    }

    private static String safeDate(String s) {
        if (s == null) return "—";
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    // ---------------- dashboard ----------------
    private void renderDashboard() {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/dashboard");
                if (r.code == 401) {
                    main.post(() -> {
                        AdminSession.logout(this);
                        startActivity(new Intent(this, AdminAuthActivity.class));
                        finish();
                    });
                    return;
                }
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONObject s = r.json.optJSONObject("stats");
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Total Payments: " + s.optInt("totalPayments")
                            + "\nPending: " + s.optInt("pending")
                            + "\nApproved: " + s.optInt("approved")
                            + "\nRejected: " + s.optInt("rejected")
                            + "\nApproved Revenue: ৳" + s.optInt("approvedRevenue")
                            + "\nActive Subscriptions: " + s.optInt("activeSubscriptions")
                            + "\nExpired: " + s.optInt("expiredSubscriptions")
                            + "\nTotal Users: " + s.optInt("totalUsers")));
                    lastPending = s.optInt("pending", lastPending);
                    pendingBadge.setText("Pending: " + s.optInt("pending"));
                });
            } catch (Exception e) {
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Offline: " + e.getMessage()));
                });
            }
        });
    }

    // ---------------- payments ----------------
    private void renderPayments(String status, String search) {
        net.execute(() -> {
            try {
                String q = AdminApi.qs("status", status, "search", search);
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/payments" + (q.isEmpty() ? "" : "?" + q));
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONArray arr = r.json.optJSONArray("payments");
                if (arr == null) arr = new JSONArray();
                final JSONArray list = arr;
                main.post(() -> {
                    content.removeAllViews();
                    LinearLayout tools = new LinearLayout(this);
                    tools.setOrientation(LinearLayout.HORIZONTAL);
                    EditText sq = new EditText(this);
                    sq.setHint("Search user/TxID");
                    sq.setText(search);
                    sq.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                    Button go = btn("Go");
                    go.setOnClickListener(v -> renderPayments(status, sq.getText().toString()));
                    Button tog = btn("PENDING".equals(status) ? "Show ALL" : "Show PENDING");
                    tog.setOnClickListener(v -> renderPayments("PENDING".equals(status) ? "" : "PENDING", sq.getText().toString()));
                    tools.addView(sq);
                    tools.addView(go);
                    tools.addView(tog);
                    content.addView(tools);
                    if (list.length() == 0) content.addView(tv("No payments."));
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject p = list.optJSONObject(i);
                        LinearLayout card = new LinearLayout(this);
                        styleCard(card);
                        TextView t = new TextView(this);
                        t.setText("#" + p.optInt("id") + " " + p.optString("userName") + " (ID " + p.optInt("userId") + ")"
                                + "\n" + p.optString("packageName") + " ৳" + p.optInt("amount")
                                + " via " + p.optString("paymentMethodName") + " (" + p.optString("walletNumber") + ")"
                                + "\nTxID: " + p.optString("transactionId")
                                + "\nSubmitted: " + safeDate(p.optString("submittedAt", "")));
                        t.setTextSize(14);
                        card.addView(t);
                        card.addView(statusPill(p.optString("status")));
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(0, dp(10), 0, 0);
                        Button c = btn("Copy TxID");
                        styleNeutral(c);
                        c.setOnClickListener(v -> copy("txid", p.optString("transactionId")));
                        row.addView(c);
                        if ("PENDING".equals(p.optString("status"))) {
                            Button a = btn("Approve");
                            styleApprove(a);
                            a.setOnClickListener(v -> confirm("Approve payment #" + p.optInt("id") + "?", () -> review(p.optInt("id"), true, null)));
                            Button rj = btn("Reject");
                            styleReject(rj);
                            rj.setOnClickListener(v -> askReason(p.optInt("id")));
                            row.addView(a);
                            row.addView(rj);
                        }
                        card.addView(row);
                        content.addView(card);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Offline: " + e.getMessage()));
                });
            }
        });
    }

    private void confirm(String msg, Runnable action) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> action.run())
                .setNegativeButton("No", null).show();
    }

    private void askReason(int id) {
        EditText in = new EditText(this);
        in.setHint("Reason (e.g. Transaction ID could not be verified)");
        new AlertDialog.Builder(this).setTitle("Reject payment #" + id).setView(in)
                .setPositiveButton("Reject", (d, w) -> review(id, false, in.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void review(int id, boolean approve, String reason) {
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                if (!approve) body.put("reason", reason == null || reason.isEmpty() ? "Transaction ID could not be verified." : reason);
                AdminApi.Resp r = AdminApi.post(this, "/api/admin/payments/" + id + (approve ? "/approve" : "/reject"), body);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? (approve ? "Approved" : "Rejected") : r.json.optString("error", "Failed"), Toast.LENGTH_LONG).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ---------------- users ----------------
    private void renderUsers(String search) {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/users" + (search.isEmpty() ? "" : "?" + AdminApi.qs("search", search)));
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONArray arr = r.json.optJSONArray("users");
                if (arr == null) arr = new JSONArray();
                final JSONArray list = arr;
                main.post(() -> {
                    content.removeAllViews();
                    LinearLayout tools = new LinearLayout(this);
                    tools.setOrientation(LinearLayout.HORIZONTAL);
                    EditText sq = new EditText(this);
                    sq.setHint("Search name/ID/email");
                    sq.setText(search);
                    sq.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                    Button go = btn("Search");
                    go.setOnClickListener(v -> renderUsers(sq.getText().toString()));
                    tools.addView(sq);
                    tools.addView(go);
                    content.addView(tools);
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject u = list.optJSONObject(i);
                        LinearLayout card = new LinearLayout(this);
                        styleCard(card);
                        TextView t = new TextView(this);
                        t.setTextSize(14);
                        t.setText(u.optString("name") + " · ID " + u.optInt("id")
                                + "\n" + u.optString("email", "") + u.optString("phone", "")
                                + "\nPackage: " + u.optString("currentPackageName", "—")
                                + "\nStart: " + u.optString("packageStartDate", "—")
                                + "\nExpiry: " + u.optString("packageExpireDate", "—"));
                        card.addView(t);
                        LinearLayout statusRow = new LinearLayout(this);
                        statusRow.setOrientation(LinearLayout.HORIZONTAL);
                        statusRow.addView(statusPill(u.optString("status")));
                        TextView gap = new TextView(this);
                        gap.setText("  ");
                        statusRow.addView(gap);
                        statusRow.addView(statusPill(u.optBoolean("accessEnabled", true) ? "ON" : "OFF"));
                        card.addView(statusRow);
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(0, dp(10), 0, 0);
                        Button tog = btn(u.optBoolean("accessEnabled", true) ? "Disable" : "Enable");
                        if (u.optBoolean("accessEnabled", true)) styleReject(tog); else styleApprove(tog);
                        tog.setOnClickListener(v -> setAccess(u.optInt("id"), !u.optBoolean("accessEnabled", true)));
                        Button assign = btn("Assign pkg");
                        styleNeutral(assign);
                        assign.setOnClickListener(v -> askAssign(u.optInt("id")));
                        row.addView(tog);
                        row.addView(assign);
                        card.addView(row);
                        content.addView(card);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Offline: " + e.getMessage()));
                });
            }
        });
    }

    private void setAccess(int id, boolean enabled) {
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("accessEnabled", enabled);
                AdminApi.Resp r = AdminApi.patch(this, "/api/admin/users/" + id + "/access", b);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? "Updated" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void askAssign(int userId) {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/packages");
                JSONArray arr = r.ok() ? r.json.optJSONArray("packages") : new JSONArray();
                if (arr == null) arr = new JSONArray();
                cachePackages = arr;
                String[] names = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) names[i] = arr.optJSONObject(i).optString("name");
                main.post(() -> new AlertDialog.Builder(this).setTitle("Assign package to user " + userId)
                        .setItems(names, (d, which) -> {
                            int pkgId = cachePackages.optJSONObject(which).optInt("id");
                            assignPkg(userId, pkgId);
                        }).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void assignPkg(int userId, int pkgId) {
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("packageId", pkgId);
                AdminApi.Resp r = AdminApi.post(this, "/api/admin/users/" + userId + "/assign-package", b);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? "Assigned" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ---------------- packages ----------------
    private void renderPackages() {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/packages");
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONArray arr = r.json.optJSONArray("packages");
                if (arr == null) arr = new JSONArray();
                final JSONArray list = arr;
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Packages are database-driven (User App never hardcodes prices)."));
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject p = list.optJSONObject(i);
                        LinearLayout card = new LinearLayout(this);
                        styleCard(card);
                        TextView t = new TextView(this);
                        t.setTextSize(15);
                        t.setTypeface(null, android.graphics.Typeface.BOLD);
                        t.setText(p.optString("name") + "\n৳" + p.optInt("price") + " · " + p.optInt("durationDays") + " days");
                        card.addView(t);
                        card.addView(statusPill(p.optString("status")));
                        Button tog = btn("active".equals(p.optString("status")) ? "Deactivate" : "Activate");
                        if ("active".equals(p.optString("status"))) styleReject(tog); else styleApprove(tog);
                        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        tlp.setMargins(0, dp(10), 0, 0);
                        tog.setLayoutParams(tlp);
                        tog.setOnClickListener(v -> togglePackage(p));
                        card.addView(tog);
                        content.addView(card);
                    }
                    Button add = btn("Add package (৳20 / 7d style)");
                    add.setOnClickListener(v -> addPackageForm());
                    content.addView(add);
                });
            } catch (Exception e) {
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Offline: " + e.getMessage()));
                });
            }
        });
    }

    private void togglePackage(JSONObject p) {
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("status", "active".equals(p.optString("status")) ? "inactive" : "active");
                AdminApi.Resp r = AdminApi.put(this, "/api/admin/packages/" + p.optInt("id"), b);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? "Saved" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void addPackageForm() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        EditText n = new EditText(this); n.setHint("Name");
        EditText pr = new EditText(this); pr.setHint("Price"); pr.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText d = new EditText(this); d.setHint("Duration days"); d.setInputType(InputType.TYPE_CLASS_NUMBER);
        f.addView(n); f.addView(pr); f.addView(d);
        new AlertDialog.Builder(this).setTitle("New package").setView(f)
                .setPositiveButton("Create", (x, y) -> net.execute(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("name", n.getText().toString());
                        b.put("price", Integer.parseInt("0" + pr.getText().toString()));
                        b.put("durationDays", Integer.parseInt("0" + d.getText().toString()));
                        AdminApi.Resp r = AdminApi.post(this, "/api/admin/packages", b);
                        main.post(() -> {
                            Toast.makeText(this, r.ok() ? "Created" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                            render();
                        });
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                })).setNegativeButton("Cancel", null).show();
    }

    // ---------------- payment methods ----------------
    private void renderMethods() {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/payment-methods");
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONArray arr = r.json.optJSONArray("paymentMethods");
                if (arr == null) arr = new JSONArray();
                final JSONArray list = arr;
                main.post(() -> {
                    content.removeAllViews();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject m = list.optJSONObject(i);
                        LinearLayout card = new LinearLayout(this);
                        styleCard(card);
                        TextView t = new TextView(this);
                        t.setTextSize(14);
                        t.setText(m.optString("name") + " · " + m.optString("walletNumber")
                                + " (" + m.optString("accountType", "") + ")"
                                + "\n" + m.optString("instructions", ""));
                        card.addView(t);
                        card.addView(statusPill(m.optString("status")));
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(0, dp(10), 0, 0);
                        Button tog = btn("active".equals(m.optString("status")) ? "Deactivate" : "Activate");
                        if ("active".equals(m.optString("status"))) styleReject(tog); else styleApprove(tog);
                        tog.setOnClickListener(v -> toggleMethod(m));
                        Button edit = btn("Edit number");
                        styleNeutral(edit);
                        edit.setOnClickListener(v -> editMethod(m));
                        row.addView(tog);
                        row.addView(edit);
                        card.addView(row);
                        content.addView(card);
                    }
                    Button add = btn("Add method");
                    add.setOnClickListener(v -> addMethodForm());
                    content.addView(add);
                });
            } catch (Exception e) {
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Offline: " + e.getMessage()));
                });
            }
        });
    }

    private void toggleMethod(JSONObject m) {
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("status", "active".equals(m.optString("status")) ? "inactive" : "active");
                AdminApi.Resp r = AdminApi.put(this, "/api/admin/payment-methods/" + m.optInt("id"), b);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? "Saved" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void editMethod(JSONObject m) {
        EditText in = new EditText(this);
        in.setText(m.optString("walletNumber"));
        new AlertDialog.Builder(this).setTitle("Wallet number for " + m.optString("name")).setView(in)
                .setPositiveButton("Save", (d, w) -> net.execute(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("walletNumber", in.getText().toString());
                        AdminApi.Resp r = AdminApi.put(this, "/api/admin/payment-methods/" + m.optInt("id"), b);
                        main.post(() -> {
                            Toast.makeText(this, r.ok() ? "Saved — User App updates on next fetch" : r.json.optString("error", "Failed"), Toast.LENGTH_LONG).show();
                            render();
                        });
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                })).setNegativeButton("Cancel", null).show();
    }

    private void addMethodForm() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        EditText n = new EditText(this); n.setHint("Name (bKash/Nagad/...)");
        EditText num = new EditText(this); num.setHint("Wallet number");
        EditText ins = new EditText(this); ins.setHint("Instructions");
        f.addView(n); f.addView(num); f.addView(ins);
        new AlertDialog.Builder(this).setTitle("New payment method").setView(f)
                .setPositiveButton("Create", (x, y) -> net.execute(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("name", n.getText().toString());
                        b.put("walletNumber", num.getText().toString());
                        b.put("instructions", ins.getText().toString());
                        AdminApi.Resp r = AdminApi.post(this, "/api/admin/payment-methods", b);
                        main.post(() -> {
                            Toast.makeText(this, r.ok() ? "Created" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                            render();
                        });
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                })).setNegativeButton("Cancel", null).show();
    }

    // ---------------- versions ----------------
    private void renderVersions() {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/versions");
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONArray arr = r.json.optJSONArray("versions");
                if (arr == null) arr = new JSONArray();
                final JSONArray list = arr;
                main.post(() -> {
                    content.removeAllViews();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject v = list.optJSONObject(i);
                        content.addView(tv(v.optString("platform") + ": latest " + v.optString("latestVersion")
                                + ", min " + v.optString("minimumSupportedVersion")
                                + ", required=" + v.optBoolean("updateRequired")
                                + "\nURL: " + v.optString("updateUrl")
                                + "\n" + v.optString("message")));
                    }
                    LinearLayout f = new LinearLayout(this);
                    f.setOrientation(LinearLayout.VERTICAL);
                    EditText latest = new EditText(this); latest.setHint("Latest (1.5.0)");
                    EditText min = new EditText(this); min.setHint("Minimum (1.3.0)");
                    EditText url = new EditText(this); url.setHint("Update URL");
                    EditText msg = new EditText(this); msg.setHint("Message");
                    f.addView(latest); f.addView(min); f.addView(url); f.addView(msg);
                    Button save = btn("Save android version");
                    save.setOnClickListener(v -> net.execute(() -> {
                        try {
                            JSONObject b = new JSONObject();
                            if (!latest.getText().toString().isEmpty()) b.put("latestVersion", latest.getText().toString());
                            if (!min.getText().toString().isEmpty()) b.put("minimumSupportedVersion", min.getText().toString());
                            b.put("updateUrl", url.getText().toString());
                            b.put("message", msg.getText().toString());
                            AdminApi.Resp rr = AdminApi.put(this, "/api/admin/versions/android", b);
                            main.post(() -> {
                                Toast.makeText(this, rr.ok() ? "Saved" : rr.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                                render();
                            });
                        } catch (Exception e) {
                            main.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }));
                    content.addView(f);
                    content.addView(save);
                });
            } catch (Exception e) {
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("Offline: " + e.getMessage()));
                });
            }
        });
    }
}
