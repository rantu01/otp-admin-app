package com.otpfetch.admin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
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
    // Table View / Card View toggle (payments + users + withdrawals). Persists per session.
    private String payViewMode = "card";

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
        String[] tabs = {"dashboard", "profit", "withdraw", "payments", "recvpay", "users", "packages", "methods", "versions"};
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
        refreshBtn.setOnClickListener(v -> {
            UiBusy.setBusy(refreshBtn, "Loading...");
            render();
            main.postDelayed(() -> UiBusy.setIdle(refreshBtn), 800);
        });
        logoutBtn.setOnClickListener(v -> {
            UiBusy.setBusy(logoutBtn, "Logging out...");
            // Best-effort backend logout: frees the single live session so the
            // same admin account can log in from another device afterwards.
            net.execute(() -> {
                try {
                    AdminApi.post(this, "/api/auth/logout", new JSONObject());
                } catch (Exception ignored) {}
                main.post(() -> {
                    AdminSession.logout(this);
                    startActivity(new Intent(this, AdminAuthActivity.class));
                    finish();
                });
            });
        });
        ensureChannel();
        requestNotifPermission();
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
                if (nm != null) {
                    Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    NotificationChannel ch = new NotificationChannel(CH, "Admin payments", NotificationManager.IMPORTANCE_HIGH);
                    ch.setDescription("New payment / withdrawal alerts with sound");
                    try { ch.setSound(sound, attrs); } catch (Exception ignored) {}
                    try { ch.enableVibration(true); } catch (Exception ignored) {}
                    nm.createNotificationChannel(ch);
                }
            }
        } catch (Exception ignored) {}
    }

    private void requestNotifPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9002);
                }
            }
        } catch (Exception ignored) {}
    }

    /** Audible + visible alert; tapping opens the payments tab. Works in background while the process lives. */
    private void notifyNewPayment(int pending) {
        try {
            Intent open = new Intent(this, AdminMainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 9001, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                    .setContentTitle("New payment received for verification")
                    .setContentText(pending + " pending payment(s) — tap to review")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setSound(sound)
                    .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(9001, b.build());
            try {
                Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vib.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
                    else vib.vibrate(400);
                }
            } catch (Exception ignored) {}
            Toast.makeText(this, "New payment received for verification", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    // ---------------- rendering ----------------
    private void render() {
        content.removeAllViews();
        headerView.setText("NesaAdmin · " + tab.toUpperCase() + " · " + AdminSession.getBase(this));
        TextView loading = new TextView(this);
        loading.setText("Loading...");
        content.addView(loading);
        switch (tab) {
            case "payments": renderPayments("PENDING", ""); break;
            case "recvpay": renderRecvPayments("", ""); break;
            case "profit": renderProfit(); break;
            case "withdraw": renderWithdraw(); break;
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
        if ("APPROVED".equals(status) || "active".equals(status) || "ON".equals(status) || "FREE".equals(status) || "free".equals(status)) {
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

    /**
     * Generic button loading state for admin actions: disables the tapped
     * button with loader text while the network call runs. Success paths
     * call render() (which rebuilds the list, dropping the busy button);
     * failure paths re-enable it below via {@code failIdle}.
     */
    private void failIdle(Button src) {
        if (src != null) UiBusy.setIdle(src);
    }

    // ---------------- dashboard ----------------
    private void renderDashboard() {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/dashboard");
                if (r.code == 401) {
                    final String msg = r.json.optString("error", "Session expired.");
                    main.post(() -> {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        AdminSession.logout(this);
                        startActivity(new Intent(this, AdminAuthActivity.class));
                        finish();
                    });
                    return;
                }
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONObject s = r.json.optJSONObject("stats");
                JSONObject profit = r.json.optJSONObject("profit");
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
                    if (profit != null) content.addView(tv(profitSummaryText(profit)));
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

    // ---------------- profit & withdrawals (transparent split ledger) ----------------
    private String profitSummaryText(JSONObject p) {
        if (p == null) return "Profit: —";
        StringBuilder sb = new StringBuilder();
        sb.append("Daily Profit: ৳").append(p.optInt("daily"))
          .append("\nWeekly Profit: ৳").append(p.optInt("weekly"))
          .append("\nTotal Earned: ৳").append(p.optInt("total"))
          .append("\nWithdrawn: ৳").append(p.optInt("withdrawnTotal"))
          .append(" (").append(p.optInt("withdrawalCount")).append(")")
          .append("\nRemaining Available: ৳").append(p.optInt("remaining"));
        JSONArray people = p.optJSONArray("people");
        if (people != null) {
            for (int i = 0; i < people.length(); i++) {
                JSONObject x = people.optJSONObject(i);
                sb.append("\n").append(x.optString("name")).append(" (").append(x.optInt("pct")).append("%)")
                  .append(": entitled ৳").append(x.optDouble("totalEntitled"))
                  .append(" · now ৳").append(x.optDouble("withdrawableNow"));
            }
        }
        return sb.toString();
    }

    private void renderProfit() {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/profits");
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONObject profit = r.json.optJSONObject("profit");
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv(profitSummaryText(profit)));
                    content.addView(tv("Profit = APPROVED payments only. Withdrawals deduct from Remaining. Split: Alamin 20% · Rantu 40% · Rony 40%."));
                });
            } catch (Exception e) {
                main.post(() -> { content.removeAllViews(); content.addView(tv("Offline: " + e.getMessage())); });
            }
        });
    }

    private void renderWithdraw() {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.get(this, "/api/admin/withdrawals");
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONObject profit = r.json.optJSONObject("profit");
                JSONArray arr = r.json.optJSONArray("withdrawals");
                if (arr == null) arr = new JSONArray();
                final JSONArray list = arr;
                final JSONObject pf = profit;
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv(profitSummaryText(pf)));
                    // New withdrawal form (person + phone + amount + note)
                    LinearLayout f = new LinearLayout(this);
                    f.setOrientation(LinearLayout.VERTICAL);
                    styleCard(f);
                    TextView h = new TextView(this);
                    h.setText("New withdrawal (deducts from Remaining)");
                    h.setTypeface(null, android.graphics.Typeface.BOLD);
                    f.addView(h);
                    EditText person = new EditText(this); person.setHint("Person: alamin / rantu / rony");
                    EditText phone = new EditText(this); phone.setHint("Receiver phone (optional)"); phone.setInputType(InputType.TYPE_CLASS_PHONE);
                    EditText amount = new EditText(this); amount.setHint("Amount ৳"); amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    EditText note = new EditText(this); note.setHint("Note (optional)");
                    f.addView(person); f.addView(phone); f.addView(amount); f.addView(note);
                    Button go = btn("Record payout");
                    styleApprove(go);
                    go.setOnClickListener(v -> {
                        UiBusy.setBusy(go, "Saving...");
                        net.execute(() -> {
                            try {
                                JSONObject b = new JSONObject();
                                b.put("person", person.getText().toString());
                                b.put("phone", phone.getText().toString());
                                try { b.put("amount", Double.parseDouble("0" + amount.getText().toString())); }
                                catch (Exception ex) { b.put("amount", 0); }
                                b.put("note", note.getText().toString());
                                AdminApi.Resp rr = AdminApi.post(this, "/api/admin/withdrawals", b);
                                main.post(() -> {
                                    Toast.makeText(this, rr.ok() ? "Recorded" : rr.json.optString("error", "Failed"), Toast.LENGTH_LONG).show();
                                    render();
                                });
                            } catch (Exception e) { main.post(() -> {
                                failIdle(go);
                                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }); }
                        });
                    });
                    f.addView(go);
                    content.addView(f);
                    // History: table/card toggle
                    Button tog = btn("Withdrawals: " + ("table".equals(payViewMode) ? "TABLE" : "CARD") + " (toggle)");
                    styleNeutral(tog);
                    tog.setOnClickListener(v -> { payViewMode = "table".equals(payViewMode) ? "card" : "table"; render(); });
                    content.addView(tog);
                    if (list.length() == 0) { content.addView(tv("No withdrawals yet.")); return; }
                    if ("table".equals(payViewMode)) {
                        content.addView(withdrawTable(list));
                    } else {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject w = list.optJSONObject(i);
                            content.addView(tv("#" + w.optInt("id") + " " + w.optString("person")
                                    + " → " + w.optString("phone") + " ৳" + w.optDouble("amount")
                                    + "\nDate: " + w.optString("createdAt", "").substring(0, Math.min(16, w.optString("createdAt", "").length()))
                                    + "\nRemaining after: ৳" + w.optDouble("remainingAfter")
                                    + (w.optString("note", "").isEmpty() ? "" : "\nNote: " + w.optString("note"))));
                        }
                    }
                });
            } catch (Exception e) {
                main.post(() -> { content.removeAllViews(); content.addView(tv("Offline: " + e.getMessage())); });
            }
        });
    }

    private TableLayout withdrawTable(JSONArray list) {
        TableLayout t = new TableLayout(this);
        t.setStretchAllColumns(true);
        String[] head = {"#", "Person", "Phone", "Amt", "Date", "Left"};
        TableRow hr = new TableRow(this);
        for (String h : head) { TextView c = new TextView(this); c.setText(h); c.setTypeface(null, android.graphics.Typeface.BOLD); c.setPadding(dp(6), dp(6), dp(6), dp(6)); hr.addView(c); }
        t.addView(hr);
        for (int i = 0; i < list.length(); i++) {
            JSONObject w = list.optJSONObject(i);
            TableRow row = new TableRow(this);
            String dt = w.optString("createdAt", "");
            if (dt.length() > 10) dt = dt.substring(0, 10);
            String[] cells = {String.valueOf(w.optInt("id")), w.optString("person"), w.optString("phone"), "৳" + w.optDouble("amount"), dt, "৳" + w.optDouble("remainingAfter")};
            for (String c : cells) { TextView tv = new TextView(this); tv.setText(c); tv.setTextSize(11); tv.setPadding(dp(6), dp(6), dp(6), dp(6)); row.addView(tv); }
            t.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.setHorizontalScrollBarEnabled(true);
        // Wrap in horizontal scroll via container
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.addView(t);
        ScrollView outer = new ScrollView(this);
        outer.addView(wrap);
        TableLayout holder = new TableLayout(this);
        holder.addView(outer);
        return holder;
    }

    private LinearLayout paymentTable(JSONArray list) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        styleCard(wrap);
        TableLayout t = new TableLayout(this);
        t.setStretchAllColumns(true);
        String[] head = {"#", "User", "Pkg", "Amt", "TxID", "Status"};
        TableRow hr = new TableRow(this);
        for (String h : head) { TextView c = new TextView(this); c.setText(h); c.setTypeface(null, android.graphics.Typeface.BOLD); c.setPadding(dp(6), dp(6), dp(6), dp(6)); hr.addView(c); }
        t.addView(hr);
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            TableRow row = new TableRow(this);
            final int pid = p.optInt("id");
            final String tx = p.optString("transactionId");
            // AUTO badge: approved by SMS auto-verify, no admin involvement.
            final String st = p.optString("status") + (p.optBoolean("autoVerified", false) ? " · AUTO" : "");
            String[] cells = {"#" + pid, p.optString("userName"), p.optString("packageName") + " ৳" + p.optInt("amount"), "৳" + p.optInt("amount"), tx.length() > 12 ? tx.substring(0, 12) + "…" : tx, st};
            for (String c : cells) { TextView tv = new TextView(this); tv.setText(c); tv.setTextSize(11); tv.setPadding(dp(6), dp(6), dp(6), dp(6)); row.addView(tv); }
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if ("PENDING".equals(st)) confirm("Approve payment #" + pid + "?", () -> review(pid, true, null));
                else copy("txid", tx);
            });
            t.addView(row);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(t);
        wrap.addView(hs);
        TextView hint = new TextView(this);
        hint.setText("Tap a PENDING row to approve · tap others to copy TxID.");
        hint.setTextSize(11);
        wrap.addView(hint);
        return wrap;
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
                    Button view = btn(("table".equals(payViewMode) ? "TABLE" : "CARD") + " (toggle)");
                    styleNeutral(view);
                    view.setOnClickListener(v -> { payViewMode = "table".equals(payViewMode) ? "card" : "table"; render(); });
                    tools.addView(sq);
                    tools.addView(go);
                    tools.addView(tog);
                    content.addView(tools);
                    content.addView(view);
                    if (list.length() == 0) { content.addView(tv("No payments.")); return; }
                    if ("table".equals(payViewMode)) {
                        content.addView(paymentTable(list));
                        return;
                    }
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject p = list.optJSONObject(i);
                        LinearLayout card = new LinearLayout(this);
                        styleCard(card);
                        TextView t = new TextView(this);
                        String cardText = "#" + p.optInt("id") + " " + p.optString("userName") + " (ID " + p.optInt("userId") + ")"
                                + "\n" + p.optString("packageName") + " ৳" + p.optInt("amount")
                                + " via " + p.optString("paymentMethodName") + " (" + p.optString("walletNumber") + ")"
                                + "\nTxID: " + p.optString("transactionId")
                                + "\nSubmitted: " + safeDate(p.optString("submittedAt", ""))
                                + (p.optBoolean("autoVerified", false) ? "\n✓ Auto-verified via bKash SMS (no manual review)" : "");
                        if (!p.optString("verifyNote", "").isEmpty()) {
                            cardText += "\nNote: " + p.optString("verifyNote");
                        }
                        t.setText(cardText);
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
                            a.setOnClickListener(v -> confirm("Approve payment #" + p.optInt("id") + "?", () -> review(a, p.optInt("id"), true, null)));
                            Button rj = btn("Reject");
                            styleReject(rj);
                            rj.setOnClickListener(v -> askReason(rj, p.optInt("id")));
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

    private void askReason(Button src, int id) {
        EditText in = new EditText(this);
        in.setHint("Reason (e.g. Transaction ID could not be verified)");
        new AlertDialog.Builder(this).setTitle("Reject payment #" + id).setView(in)
                .setPositiveButton("Reject", (d, w) -> review(src, id, false, in.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    /** Approve/reject with button loading state (disabled + loader text). */
    private void review(Button src, int id, boolean approve, String reason) {
        if (src != null) UiBusy.setBusy(src, approve ? "Approving..." : "Rejecting...");
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
                main.post(() -> {
                    if (src != null) UiBusy.setIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void review(int id, boolean approve, String reason) {
        review(null, id, approve, reason);
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
                    Button add = btn("Add user");
                    styleApprove(add);
                    add.setOnClickListener(v -> addUserForm());
                    tools.addView(sq);
                    tools.addView(go);
                    content.addView(tools);
                    content.addView(add);
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject u = list.optJSONObject(i);
                        LinearLayout card = new LinearLayout(this);
                        styleCard(card);
                        TextView t = new TextView(this);
                        t.setTextSize(14);
                        t.setText(u.optString("name") + " · ID " + u.optInt("id") + " [" + u.optString("role", "user") + "]"
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
                        TextView gap2 = new TextView(this);
                        gap2.setText("  ");
                        statusRow.addView(gap2);
                        statusRow.addView(statusPill("free".equals(u.optString("role")) ? "FREE" : "PAID"));
                        card.addView(statusRow);
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(0, dp(10), 0, 0);
                        Button tog = btn(u.optBoolean("accessEnabled", true) ? "Disable" : "Enable");
                        if (u.optBoolean("accessEnabled", true)) styleReject(tog); else styleApprove(tog);
                        tog.setOnClickListener(v -> {
                            UiBusy.setBusy(tog, "Saving...");
                            setAccess(tog, u.optInt("id"), !u.optBoolean("accessEnabled", true));
                        });
                        Button free = btn("free".equals(u.optString("role")) ? "Make paid" : "Make free");
                        styleNeutral(free);
                        free.setOnClickListener(v -> {
                            UiBusy.setBusy(free, "Saving...");
                            setRole(free, u.optInt("id"), "free".equals(u.optString("role")) ? "user" : "free");
                        });
                        Button assign = btn("Assign pkg");
                        styleNeutral(assign);
                        assign.setOnClickListener(v -> askAssign(u.optInt("id")));
                        Button del = btn("Remove");
                        styleReject(del);
                        del.setOnClickListener(v -> confirm("Remove user " + u.optString("name") + "? History is kept.", () -> {
                            UiBusy.setBusy(del, "Removing...");
                            removeUser(del, u.optInt("id"));
                        }));
                        row.addView(tog);
                        row.addView(free);
                        row.addView(assign);
                        row.addView(del);
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

    private void setAccess(Button src, int id, boolean enabled) {
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
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setRole(Button src, int id, String role) {
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("role", role);
                AdminApi.Resp r = AdminApi.patch(this, "/api/admin/users/" + id + "/access", b);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? ("Role: " + role) : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void removeUser(Button src, int id) {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.delete(this, "/api/admin/users/" + id);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? "Removed" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void addUserForm() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        EditText n = new EditText(this); n.setHint("Name");
        EditText e = new EditText(this); e.setHint("Email or phone"); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText p = new EditText(this); p.setHint("Password (min 4)"); p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final boolean[] free = {false};
        Button roleBtn = btn("Role: user (needs package)");
        styleNeutral(roleBtn);
        roleBtn.setOnClickListener(v -> {
            free[0] = !free[0];
            roleBtn.setText(free[0] ? "Role: FREE (no payment)" : "Role: user (needs package)");
        });
        f.addView(n); f.addView(e); f.addView(p); f.addView(roleBtn);
        new AlertDialog.Builder(this).setTitle("Add user").setView(f)
                .setPositiveButton("Create", (x, y) -> net.execute(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("name", n.getText().toString());
                        b.put("email", e.getText().toString());
                        b.put("password", p.getText().toString());
                        b.put("role", free[0] ? "free" : "user");
                        AdminApi.Resp r = AdminApi.post(this, "/api/admin/users", b);
                        main.post(() -> {
                            Toast.makeText(this, r.ok() ? "Created" : r.json.optString("error", "Failed"), Toast.LENGTH_LONG).show();
                            render();
                        });
                    } catch (Exception ex) {
                        main.post(() -> Toast.makeText(this, "Error: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                })).setNegativeButton("Cancel", null).show();
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
                            Toast.makeText(this, "Assigning...", Toast.LENGTH_SHORT).show();
                            assignPkg(null, userId, pkgId);
                        }).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void assignPkg(Button src, int userId, int pkgId) {
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
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
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
                        tog.setOnClickListener(v -> {
                            UiBusy.setBusy(tog, "Saving...");
                            togglePackage(tog, p);
                        });
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(0, dp(6), 0, 0);
                        Button edit = btn("Edit");
                        styleNeutral(edit);
                        edit.setOnClickListener(v -> editPackageForm(p));
                        Button del = btn("Delete");
                        styleReject(del);
                        del.setOnClickListener(v -> confirm("Delete package " + p.optString("name") + "?", () -> {
                            UiBusy.setBusy(del, "Deleting...");
                            deletePackage(del, p.optInt("id"));
                        }));
                        row.addView(tog);
                        row.addView(edit);
                        row.addView(del);
                        card.addView(row);
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

    private void togglePackage(Button src, JSONObject p) {
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
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void editPackageForm(JSONObject p) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        EditText n = new EditText(this); n.setText(p.optString("name")); n.setHint("Name");
        EditText pr = new EditText(this); pr.setText(String.valueOf(p.optInt("price"))); pr.setHint("Price"); pr.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText d = new EditText(this); d.setText(String.valueOf(p.optInt("durationDays"))); d.setHint("Duration days"); d.setInputType(InputType.TYPE_CLASS_NUMBER);
        f.addView(n); f.addView(pr); f.addView(d);
        new AlertDialog.Builder(this).setTitle("Edit package").setView(f)
                .setPositiveButton("Save", (x, y) -> net.execute(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("name", n.getText().toString());
                        b.put("price", Integer.parseInt("0" + pr.getText().toString()));
                        b.put("durationDays", Integer.parseInt("0" + d.getText().toString()));
                        AdminApi.Resp r = AdminApi.put(this, "/api/admin/packages/" + p.optInt("id"), b);
                        main.post(() -> {
                            Toast.makeText(this, r.ok() ? "Saved" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                            render();
                        });
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                })).setNegativeButton("Cancel", null).show();
    }

    private void deletePackage(Button src, int id) {
        net.execute(() -> {
            try {
                AdminApi.Resp r = AdminApi.delete(this, "/api/admin/packages/" + id);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? "Deleted" : r.json.optString("error", "Failed"), Toast.LENGTH_LONG).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
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
                        tog.setOnClickListener(v -> {
                            UiBusy.setBusy(tog, "Saving...");
                            toggleMethod(tog, m);
                        });
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

    private void toggleMethod(Button src, JSONObject m) {
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
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
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

    // ---------------- received bKash payments (Recive payment app outbox) ----------------
    private void renderRecvPayments(String search, String status) {
        net.execute(() -> {
            try {
                String q = AdminApi.qs("search", search, "status", status);
                AdminApi.Resp r = AdminApi.get(this, "/api/received-payments" + (q.replace("=", "").isEmpty() ? "" : "?" + q));
                if (!r.ok()) throw new Exception(r.json.optString("error", "Failed"));
                JSONArray arr = r.json.optJSONArray("payments");
                if (arr == null) arr = new JSONArray();
                final JSONArray list = arr;
                main.post(() -> {
                    content.removeAllViews();
                    content.addView(tv("bKash receiver-phone outbox. Verify matches customer TrxIDs against these SMS records."));
                    LinearLayout tools = new LinearLayout(this);
                    tools.setOrientation(LinearLayout.HORIZONTAL);
                    EditText sq = new EditText(this);
                    sq.setHint("Search TrxID / sender");
                    sq.setText(search);
                    sq.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                    Button go = btn("Go");
                    go.setOnClickListener(v -> renderRecvPayments(sq.getText().toString(), status));
                    Button tog = btn(status.isEmpty() ? "Show PENDING" : "Show ALL");
                    tog.setOnClickListener(v -> renderRecvPayments(sq.getText().toString(), status.isEmpty() ? "pending" : ""));
                    tools.addView(sq);
                    tools.addView(go);
                    tools.addView(tog);
                    content.addView(tools);
                    // Verify box: paste a customer TrxID + expected amount
                    LinearLayout vf = new LinearLayout(this);
                    vf.setOrientation(LinearLayout.VERTICAL);
                    styleCard(vf);
                    TextView h = new TextView(this);
                    h.setText("Verify customer TrxID");
                    h.setTypeface(null, android.graphics.Typeface.BOLD);
                    vf.addView(h);
                    EditText tid = new EditText(this); tid.setHint("TrxID (e.g. DIJ2N7GCGS)");
                    EditText amt = new EditText(this); amt.setHint("Expected amount (optional)"); amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    vf.addView(tid); vf.addView(amt);
                    Button goV = btn("Verify");
                    styleApprove(goV);
                    goV.setOnClickListener(v -> {
                        UiBusy.setBusy(goV, "Verifying...");
                        net.execute(() -> {
                        try {
                            JSONObject b = new JSONObject();
                            b.put("trxId", tid.getText().toString());
                            if (!amt.getText().toString().isEmpty()) b.put("amount", Double.parseDouble("0" + amt.getText().toString()));
                            AdminApi.Resp rr = AdminApi.post(this, "/api/received-payments/verify", b);
                            main.post(() -> {
                                Toast.makeText(this, rr.ok() ? ("Found · amountOk=" + rr.json.optBoolean("amountOk")) : rr.json.optString("error", "Not found"), Toast.LENGTH_LONG).show();
                                render();
                            });
                        } catch (Exception e) { main.post(() -> {
                            failIdle(goV);
                            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }); }
                    });});
                    vf.addView(goV);
                    content.addView(vf);
                    if (list.length() == 0) { content.addView(tv("No received payments.")); return; }
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject p = list.optJSONObject(i);
                        LinearLayout card = new LinearLayout(this);
                        styleCard(card);
                        TextView t = new TextView(this);
                        t.setText("৳" + p.optDouble("amount") + " · " + p.optString("trxId")
                                + "\nFrom: " + p.optString("sender") + " · Fee ৳" + p.optDouble("fee")
                                + "\nDate: " + p.optString("transactionDate", "") + " " + p.optString("transactionTime", "")
                                + (p.optInt("matchedPaymentId", 0) != 0 ? "\n✓ Auto-matched to payment #" + p.optInt("matchedPaymentId") : "")
                                + "\nSMS: " + p.optString("originalMessage", "").substring(0, Math.min(120, p.optString("originalMessage", "").length())));
                        t.setTextSize(14);
                        card.addView(t);
                        card.addView(statusPill(p.optString("status")));
                        LinearLayout row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(0, dp(10), 0, 0);
                        Button c = btn("Copy TrxID");
                        styleNeutral(c);
                        c.setOnClickListener(v -> copy("trxid", p.optString("trxId")));
                        row.addView(c);
                        if ("pending".equals(p.optString("status"))) {
                            Button u = btn("Mark used");
                            styleApprove(u);
                            u.setOnClickListener(v -> confirm("Mark " + p.optString("trxId") + " as USED?", () -> {
                                UiBusy.setBusy(u, "Saving...");
                                setRecvStatus(u, p.optString("trxId"), "used");
                            }));
                            Button rj = btn("Reject");
                            styleReject(rj);
                            rj.setOnClickListener(v -> confirm("Reject " + p.optString("trxId") + "?", () -> {
                                UiBusy.setBusy(rj, "Rejecting...");
                                setRecvStatus(rj, p.optString("trxId"), "rejected");
                            }));
                            row.addView(u);
                            row.addView(rj);
                        }
                        card.addView(row);
                        content.addView(card);
                    }
                });
            } catch (Exception e) {
                main.post(() -> { content.removeAllViews(); content.addView(tv("Offline: " + e.getMessage())); });
            }
        });
    }

    private void setRecvStatus(Button src, String trxId, String status) {
        net.execute(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("status", status);
                // PATCH via AdminApi (uses call with method PATCH)
                AdminApi.Resp r = AdminApi.patch(this, "/api/received-payments/" + trxId + "/status", b);
                main.post(() -> {
                    Toast.makeText(this, r.ok() ? "Updated" : r.json.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    failIdle(src);
                    Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
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
                    save.setOnClickListener(v -> {
                        UiBusy.setBusy(save, "Saving...");
                        net.execute(() -> {
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
                            main.post(() -> {
                                failIdle(save);
                                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    });});
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
