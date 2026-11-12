package com.amiri.remote;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IR remote for a Chinese "WISDOM·SHARE Smart cloud TV" board, using the
 * phone's IR blaster. Each code set (header) has default key codes, and
 * every key can be re-tuned on the phone in the setup screen (scan codes
 * 0..255 and assign the one that does the right thing).
 */
public class MainActivity extends Activity {

    static final int POWER = 0, MUTE = 1, VOL_UP = 2, VOL_DN = 3, CH_UP = 4, CH_DN = 5,
            OK = 6, UP = 7, DOWN = 8, LEFT = 9, RIGHT = 10, HOME = 11, BACK = 12, MENU = 13,
            SOURCE = 14, EXIT = 15, SLEEP = 16, MOUSE = 17;
    static final int KEY_COUNT = 18;

    static final String[] KEY_NAMES = {
            "روشن/خاموش", "بی‌صدا", "صدا +", "صدا −", "کانال +", "کانال −",
            "OK", "بالا ▲", "پایین ▼", "چپ ◀", "راست ▶", "خانه", "برگشت", "منو", "ورودی (Source)", "خروج (EXIT)", "تایمر خواب", "موس"};

    static final String[] SET_NAMES = {"کد ۱", "کد ۲", "کد ۳", "کد ۴", "کد ۵"};
    static final int[][] ADDR = {{0x00, 0xBF}, {0x20, 0xDF}, {0x07, 0x07}, {0x00, 0xFF}, {0x04, 0xFB}};
    static final boolean[] SAMSUNG = {false, false, true, false, false};
    static final int[][] CODES = {
            // POWER MUTE VOL+ VOL- CH+  CH-  OK   UP   DOWN LEFT RIGHT HOME BACK MENU SOURCE
            {0x00, 0x01, 0x48, 0x49, 0x4A, 0x4B, 0x10, 0x13, 0x14, 0x11, 0x12, 0x64, 0x15, 0x40, 0x41, 0x1C, 0x53, 0x10},
            {11, 20, 10, 64, 2, 9, 88, 85, 90, -1, 22, -1, -1, 78, 27, 75, 83, 16},
            {2, 15, 7, 11, 18, 16, 104, 96, 97, 101, 98, 121, 88, 26, 1, 45, 0x53, 0x10},
            {0x45, 0x47, 0x15, 0x07, 0x09, 0x16, 0x1C, 0x18, 0x52, 0x08, 0x5A, 0x44, 0x43, 0x40, 0x46, 0x4A, 0x53, 0x10},
            {0x08, 0x09, 0x02, 0x03, 0x00, 0x01, 0x44, 0x40, 0x41, 0x07, 0x06, 0x7C, 0x28, 0x43, 0x0B, 0x5B, 0x53, 0x10},
    };

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private int set = 0;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService irThread = Executors.newSingleThreadExecutor();
    private float dp;
    private boolean hasIr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        set = 1; // only "code 2" (NEC header 0x20DF) works on this TV
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        hasIr = ir != null && ir.hasIrEmitter();
        getWindow().setStatusBarColor(Color.parseColor("#EDEFF2"));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        int[][] known = {{POWER, 11}, {CH_UP, 2}, {CH_DN, 9}, {VOL_UP, 10}, {VOL_DN, 64}, {MUTE, 20}, {OK, 88}, {UP, 85}, {DOWN, 90}, {RIGHT, 22}, {MENU, 78}, {SOURCE, 27}, {EXIT, 75}, {SLEEP, 83}, {MOUSE, 16}};
        SharedPreferences.Editor ke = prefs.edit();
        for (int[] kv : known) {
            ke.putInt("s" + set + "_k" + kv[0], kv[1]).putBoolean("ok_k" + kv[0], true);
        }
        if (prefs.getInt("s" + set + "_k" + VOL_DN, -2) == 0x41) ke.remove("s" + set + "_k" + VOL_DN).remove("ok_k" + VOL_DN);
        if (prefs.getInt("s" + set + "_k" + BACK, -2) == 0x1E) ke.remove("s" + set + "_k" + BACK).remove("ok_k" + BACK);
        if (prefs.getInt("s" + set + "_k" + RIGHT, -2) == 0x4B) ke.remove("s" + set + "_k" + RIGHT).remove("ok_k" + RIGHT);
        int[] wrong = {0x41, 0x4A, 0x47, 0x4D, 0x49, 0x0F, 0x1E, 0x4E, 0x03, 0x53, 0x4B};
        for (int k : new int[]{VOL_DN, OK, UP, DOWN, LEFT, RIGHT, HOME, BACK, MENU}) {
            int v = prefs.getInt("s" + set + "_k" + k, -2);
            for (int w : wrong) if (v == w) { ke.remove("s" + set + "_k" + k).remove("ok_k" + k); }
        }
        ke.apply();
        if (prefs.getInt("s" + set + "_k" + MUTE, -1) == 0x53) {
            prefs.edit().remove("s" + set + "_k" + MUTE).remove("ok_k" + MUTE).apply();
        }
        if (!prefs.getBoolean("ok_k" + POWER, false)) {
            prefs.edit().putInt("s" + set + "_k" + POWER, 0x0B).putBoolean("ok_k" + POWER, true).apply();
        }
        showRemote();
    }

    @Override
    public void onBackPressed() {
        if (sweepNum != null) {
            showRemote();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- IR ----------

    static int[] buildPattern(int a1, int a2, int cmd, boolean samsung) {
        int[] bytes = {a1 & 0xFF, a2 & 0xFF, cmd & 0xFF, (~cmd) & 0xFF};
        int[] p = new int[2 + 64 + 1];
        int i = 0;
        p[i++] = samsung ? 4500 : 9000;
        p[i++] = 4500;
        for (int b : bytes) {
            for (int k = 0; k < 8; k++) {
                p[i++] = 560;
                p[i++] = ((b >> k) & 1) == 1 ? 1690 : 560;
            }
        }
        p[i] = 560;
        return p;
    }

    private int codeFor(int key) {
        return prefs.getInt("s" + set + "_k" + key, CODES[set][key]);
    }

    private void send(int key) {
        int c = codeFor(key);
        if (c < 0) {
            toast("کد «" + KEY_NAMES[key] + "» هنوز پیدا نشده");
            return;
        }
        sendRaw(c);
    }

    private void sendRaw(int cmd) {
        if (!hasIr) return;
        final int s = set;
        final int[] pattern = buildPattern(ADDR[s][0], ADDR[s][1], cmd, SAMSUNG[s]);
        irThread.execute(() -> {
            try {
                ir.transmit(38000, pattern);
            } catch (Exception ex) {
                handler.post(() -> toast("خطا در ارسال: " + ex.getMessage()));
            }
        });
    }

    // ---------- Remote screen ----------

    private void showRemote() {
        pauseSweep();
        sweepNum = null;
        sweepToggle = null;

        LinearLayout root = page();

        root.addView(title("ریموت تلویزیون"));

        status = new TextView(this);
        status.setText(hasIr
                ? "ریموت امیری"
                : "این گوشی فرستنده مادون‌قرمز (IR) ندارد");
        status.setTextColor(Color.parseColor(hasIr ? "#8A9099" : "#FF7070"));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, px(6), 0, px(12));
        root.addView(status);

        updateBanner = actionButton("⬇  نسخه جدید آماده است — نصب کن", "#F5A623", v -> startUpdate());
        updateBanner.setVisibility(View.GONE);
        LinearLayout bannerRow = row();
        bannerRow.addView(updateBanner, weighted(56));
        root.addView(bannerRow);

        // Layout copied from the physical RM-014S+ remote.
        LinearLayout top = row();
        top.addView(remoteButton("⏻", POWER, false, "#D32F2F", 24), weighted(58));
        top.addView(new View(this), weighted(58));
        top.addView(remoteButton("🔇", MUTE, false, "#2E9E4F", 22), weighted(58));
        root.addView(top);
        root.addView(space(8));

        LinearLayout menuRow = row();
        menuRow.addView(remoteButton("MENU", MENU, false, "#FFFFFF", 15), weighted(48));
        menuRow.addView(new View(this), weighted(48));
        menuRow.addView(rawButton("⇆ قبلی", 21, 15), weighted(48));
        root.addView(menuRow);
        root.addView(space(4));

        LinearLayout r1 = row();
        r1.addView(new View(this), weighted(66));
        r1.addView(remoteButton("▲", UP, true, "#FFFFFF", 22), weighted(66));
        r1.addView(new View(this), weighted(66));
        LinearLayout r2 = row();
        r2.addView(new View(this), weighted(66));
        r2.addView(remoteButton("OK", OK, false, "#F5A623", 20), weighted(66));
        r2.addView(remoteButton("▶", RIGHT, true, "#FFFFFF", 22), weighted(66));
        LinearLayout r3 = row();
        r3.addView(new View(this), weighted(66));
        r3.addView(remoteButton("▼", DOWN, true, "#FFFFFF", 22), weighted(66));
        r3.addView(new View(this), weighted(66));
        root.addView(r1);
        root.addView(r2);
        root.addView(r3);
        root.addView(space(4));

        LinearLayout backRow = row();
        backRow.addView(remoteButton("🖱 موس", MOUSE, false, "#FFFFFF", 15), weighted(48));
        backRow.addView(new View(this), weighted(48));
        backRow.addView(remoteButton("EXIT", EXIT, false, "#FFFFFF", 15), weighted(48));
        root.addView(backRow);
        root.addView(space(10));

        LinearLayout mid = row();
        LinearLayout vol = column();
        vol.addView(remoteButton("+\nVOL", VOL_UP, true, "#2E9E4F", 20), tall(78));
        vol.addView(remoteButton("−", VOL_DN, true, "#2E9E4F", 26), tall(78));
        LinearLayout centerCol = column();
        centerCol.setGravity(Gravity.CENTER);
        centerCol.addView(remoteButton("SOURCE", SOURCE, false, "#FFFFFF", 13), tall(60));
        centerCol.addView(remoteButton("😴 خواب", SLEEP, false, "#FFFFFF", 13), tall(60));
        LinearLayout ch = column();
        ch.addView(remoteButton("︿\nCH", CH_UP, true, "#2B4C7E", 20), tall(78));
        ch.addView(remoteButton("﹀", CH_DN, true, "#2B4C7E", 26), tall(78));
        mid.addView(vol, weightedWrap());
        LinearLayout.LayoutParams cp = weightedWrap();
        cp.gravity = Gravity.CENTER_VERTICAL;
        mid.addView(centerCol, cp);
        mid.addView(ch, weightedWrap());
        mid.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(mid);
        root.addView(space(18));

        // ---- extra keys found on this TV (sent as raw codes; -1 = not found yet) ----
        TextView more = new TextView(this);
        more.setText("دکمه‌های بیشتر");
        more.setTextColor(Color.parseColor("#8A9099"));
        more.setTextSize(13);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, 0, 0, px(4));
        root.addView(more);

        for (int r = 0; r < 3; r++) {
            LinearLayout nr = row();
            for (int c = 0; c < 3; c++) {
                int n = r * 3 + c + 1;
                nr.addView(rawButton(String.valueOf(n), digitCode(n), 20), weighted(52));
            }
            root.addView(nr);
        }
        LinearLayout zr = row();
        zr.addView(new View(this), weighted(52));
        zr.addView(rawButton("0", digitCode(0), 20), weighted(52));
        zr.addView(new View(this), weighted(52));
        root.addView(zr);
        root.addView(space(8));

        LinearLayout e1 = row();
        e1.addView(rawButton("EPG", 3, 14), weighted(50));
        e1.addView(rawButton("INFO", 14, 14), weighted(50));
        e1.addView(rawButton("FAV ⭐", 80, 14), weighted(50));
        root.addView(e1);
        LinearLayout e2 = row();
        e2.addView(rawButton("AUDIO", 89, 14), weighted(50));
        e2.addView(rawButton("SIZE", 6, 14), weighted(50));
        e2.addView(rawButton("S.MODE 🔊", 81, 13), weighted(50));
        root.addView(e2);
        LinearLayout e3 = row();
        e3.addView(rawButton("🌐 وب", 91, 14), weighted(50));
        e3.addView(rawButton("3D", 0, 14), weighted(50));
        e3.addView(rawButton("USB ▶", 8, 14), weighted(50));
        root.addView(e3);
        root.addView(space(18));

        LinearLayout setupRow = row();
        Button setup = plainButton("⚙  پیدا کردن کد دکمه‌ها (یک بار)", "#F5A623", 16);
                setup.setOnClickListener(v -> showSetup());
        setupRow.addView(setup, weighted(52));
        // all codes are found; the finder stays in code but is hidden
        setupRow.setVisibility(View.GONE);
        root.addView(setupRow);

        TextView ver = new TextView(this);
        ver.setText("نسخه " + myVersionName() + "   •   🔄 بررسی آپدیت");
        ver.setTextColor(Color.parseColor("#8A9099"));
        ver.setTextSize(13);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, px(14), 0, px(6));
        ver.setOnClickListener(v -> checkUpdate(true));
        root.addView(ver);

        checkUpdate(false);
    }

    // ---------- Setup: one automatic sweep over all 256 codes ----------

    private int sweepCode = 0;
    private boolean sweeping = false;
    private TextView sweepNum, sweepInfo, sweepDone;
    private Button sweepToggle;
    private final Runnable sweepStep = new Runnable() {
        @Override
        public void run() {
            if (!sweeping) return;
            int next = nextFreeCode(sweepCode, 1);
            if (next <= sweepCode) { // wrapped around: finished
                pauseSweep();
                toast("همه کدها امتحان شد ✔");
                return;
            }
            sweepCode = next;
            sendSweep();
            handler.postDelayed(this, 1300);
        }
    };

    private void showSetup() {
        LinearLayout root = page();
        root.addView(title("پیدا کردن کد دکمه‌ها"));

        TextView help = new TextView(this);
        help.setText("گوشی را رو به تلویزیون بگیر و «▶ شروع» را بزن.\n"
                + "گوشی هر ثانیه یک کد می‌فرستد. فقط به تلویزیون نگاه کن:\n"
                + "هر وقت تلویزیون کاری کرد (صدا زیاد شد، منو باز شد، …)\n"
                + "همان لحظه پایین بزن که چه کاری کرد. خودش ذخیره می‌شود و ادامه می‌دهد.");
        help.setTextColor(Color.parseColor("#8A9099"));
        help.setTextSize(13);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, px(6), 0, px(8));
        root.addView(help);

        sweepNum = new TextView(this);
        sweepNum.setTextColor(Color.parseColor("#1F2329"));
        sweepNum.setTextSize(44);
        sweepNum.setTypeface(Typeface.DEFAULT_BOLD);
        sweepNum.setGravity(Gravity.CENTER);
        root.addView(sweepNum);

        sweepInfo = new TextView(this);
        sweepInfo.setTextColor(Color.parseColor("#E8920C"));
        sweepInfo.setTextSize(13);
        sweepInfo.setGravity(Gravity.CENTER);
        sweepInfo.setPadding(0, 0, 0, px(6));
        root.addView(sweepInfo);

        LinearLayout ctl = row();
        ctl.addView(actionButton("◀ کد قبلی", "#FFFFFF", v -> { pauseSweep(); sweepCode = nextFreeCode(sweepCode, -1); sendSweep(); }), weighted(54));
        sweepToggle = actionButton("▶ شروع", "#F5A623", v -> toggleSweep());
        ctl.addView(sweepToggle, weighted(54));
        ctl.addView(actionButton("کد بعدی ▶", "#FFFFFF", v -> { pauseSweep(); sweepCode = nextFreeCode(sweepCode, 1); sendSweep(); }), weighted(54));
        root.addView(ctl);
        LinearLayout again = row();
        again.addView(actionButton("📡 همین کد را دوباره بفرست", "#FFFFFF", v -> sendRaw(sweepCode)), weighted(48));
        root.addView(again);

        TextView q = new TextView(this);
        q.setText("تلویزیون چه کاری کرد؟");
        q.setTextColor(Color.parseColor("#1F2329"));
        q.setTextSize(16);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setGravity(Gravity.CENTER);
        q.setPadding(0, px(12), 0, px(2));
        root.addView(q);

        int[] all = {VOL_UP, VOL_DN, MUTE, CH_UP, CH_DN, UP, DOWN, LEFT, RIGHT, OK,
                BACK, EXIT, HOME, MENU, SOURCE, POWER};
        java.util.ArrayList<Button> btns = new java.util.ArrayList<>();
        for (int key : all) {
            if (prefs.getBoolean("ok_k" + key, false)) continue; // already found
            btns.add(actionButton(KEY_NAMES[key], "#FFFFFF", v -> assignSweep(key)));
        }
        for (int n = 0; n < 10; n++) {
            if (digitCode(n) >= 0) continue; // already found
            final int d = n;
            btns.add(actionButton("عدد " + n, "#FFFFFF", v -> assignDigit(d)));
        }
        if (btns.isEmpty()) q.setText("همه دکمه‌ها پیدا شد ✔");
        LinearLayout line = null;
        for (int i = 0; i < btns.size(); i++) {
            if (i % 3 == 0) { line = row(); root.addView(line); }
            line.addView(btns.get(i), weighted(50));
        }
        if (line != null) for (int f = btns.size() % 3; f > 0 && f < 3; f++) line.addView(new View(this), weighted(50));

        sweepDone = new TextView(this);
        sweepDone.setTextColor(Color.parseColor("#2B2F36"));
        sweepDone.setTextSize(13);
        sweepDone.setPadding(px(8), px(12), px(8), px(4));
        root.addView(sweepDone);

        root.addView(space(8));
        LinearLayout last = row();
        last.addView(actionButton("پاک کردن همه", "#D32F2F", v -> resetSet()), weighted(50));
        last.addView(actionButton("✔ تمام، برگشت", "#F5A623", v -> showRemote()), weighted(50));
        root.addView(last);

        // codes 0..91 were already checked on this TV, continue after them
        sweepCode = Math.max(prefs.getInt("sweep_pos", 0), 92);
        updateSweepView();
    }

    // Codes that do something else on this TV - skipped while sweeping:
    // EPG 3, size 6, info 14, digit3 15, digit7 24, digit4 30, digit2 67, digit8 69,
    // fav 80, sound mode 81, sleep 83, digit0 86, audio 89, web 91.
    // Number keys found on this TV (-1 = not found yet); more can be found in the sweep.
    static final int[] DIGIT_BASE = {86, 66, 67, 15, 30, 29, 28, 24, 69, 76};

    private int digitCode(int n) {
        return DIGIT_BASE[n];
    }

    static final int[] OTHER_FUNCS = {3, 6, 14, 15, 24, 30, 67, 69, 80, 81, 83, 86, 89, 91};

    /** Codes already confirmed for a key are skipped while sweeping. */
    private boolean isTaken(int code) {
        for (int x : OTHER_FUNCS) if (x == code) return true;
        for (int n = 0; n < 10; n++) if (digitCode(n) == code) return true;
        for (int k = 0; k < KEY_COUNT; k++) {
            if (prefs.getBoolean("ok_k" + k, false) && codeFor(k) == code) return true;
        }
        return false;
    }

    private int nextFreeCode(int from, int dir) {
        int c = from;
        for (int i = 0; i < 256; i++) {
            c = (c + dir + 256) & 0xFF;
            if (!isTaken(c)) return c;
        }
        return from;
    }

    private void sendSweep() {
        prefs.edit().putInt("sweep_pos", sweepCode).apply();
        sendRaw(sweepCode);
        updateSweepView();
    }

    private void toggleSweep() {
        if (sweeping) {
            pauseSweep();
        } else {
            sweeping = true;
            sweepToggle.setText("⏸ توقف");
            styleButton(sweepToggle, "#D32F2F");
            sendSweep();
            handler.postDelayed(sweepStep, 1300);
        }
    }

    private void pauseSweep() {
        sweeping = false;
        handler.removeCallbacks(sweepStep);
        if (sweepToggle != null) {
            sweepToggle.setText("▶ ادامه");
            styleButton(sweepToggle, "#F5A623");
        }
    }

    private void assignSweep(int key) {
        boolean wasRunning = sweeping;
        pauseSweep();
        // People react ~1 s late, so if the sweep was running the action most
        // likely came from the code sent just before the current one.
        int code = sweepCode;
        if (wasRunning && prefs.getLong("sweep_t", 0) > System.currentTimeMillis() - 500) {
            code = nextFreeCode(sweepCode, -1);
        }
        prefs.edit()
                .putInt("s" + set + "_k" + key, code)
                .putBoolean("ok_k" + key, true)
                .apply();
        toast("کد " + code + " ← «" + KEY_NAMES[key] + "» ✔  (اگر مطمئن نیستی با ◀ ▶ چک کن)");
        showSetup();
        if (wasRunning) toggleSweep();
    }

    private void assignDigit(int n) {
        boolean wasRunning = sweeping;
        pauseSweep();
        int code = sweepCode;
        if (wasRunning && prefs.getLong("sweep_t", 0) > System.currentTimeMillis() - 500) {
            code = nextFreeCode(sweepCode, -1);
        }
        prefs.edit().putInt("dig_" + n, code).apply();
        toast("کد " + code + " ← «عدد " + n + "» ✔");
        showSetup();
        if (wasRunning) toggleSweep();
    }

    private void updateSweepView() {
        if (sweepNum == null) return;
        prefs.edit().putLong("sweep_t", System.currentTimeMillis()).apply();
        sweepNum.setText(String.valueOf(sweepCode));
        sweepInfo.setText("کد " + sweepCode + " از ۲۵۵");
        StringBuilder sb = new StringBuilder("پیدا شده‌ها:  ");
        int n = 0;
        for (int k = 0; k < KEY_COUNT; k++) {
            if (prefs.getBoolean("ok_k" + k, false)) {
                if (n++ > 0) sb.append("   •   ");
                sb.append(KEY_NAMES[k]).append(" = ").append(codeFor(k));
            }
        }
        sweepDone.setText(n == 0 ? "هنوز دکمه‌ای پیدا نشده" : sb.toString());
    }

    private void resetSet() {
        pauseSweep();
        SharedPreferences.Editor e = prefs.edit();
        for (int k = 0; k < KEY_COUNT; k++) {
            e.remove("s" + set + "_k" + k);
            e.remove("ok_k" + k);
        }
        e.putInt("sweep_pos", 0);
        e.apply();
        // power is known to be code 11 on this TV
        prefs.edit().putInt("s" + set + "_k" + POWER, 0x0B).putBoolean("ok_k" + POWER, true).apply();
        sweepCode = 0;
        toast("همه پاک شد. از اول شروع کن");
        updateSweepView();
    }

    // ---------- In-app update ----------

    static final String UPDATE_BASE =
            "https://raw.githubusercontent.com/alijojo2122joon-jpg/amiri-remote/apk/";
    private Button updateBanner;
    private long downloadId = -1;
    private BroadcastReceiver downloadDone;

    private int myVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String myVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void checkUpdate(boolean manual) {
        if (manual) toast("در حال بررسی آپدیت…");
        new Thread(() -> {
            int remote = -1;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        UPDATE_BASE + "version.txt?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                remote = Integer.parseInt(r.readLine().trim());
                r.close();
            } catch (Exception ignored) {
            }
            final int rv = remote;
            handler.post(() -> {
                if (rv > myVersionCode()) {
                    if (updateBanner != null) updateBanner.setVisibility(View.VISIBLE);
                    if (manual) toast("نسخه جدید پیدا شد");
                } else if (manual) {
                    toast(rv < 0 ? "اینترنت وصل نیست یا سرور جواب نداد" : "اپ به‌روز است ✔");
                }
            });
        }).start();
    }

    private void startUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("اجازه «نصب برنامه‌های ناشناس» را برای ریموت امیری روشن کن و برگرد");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        File old = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AmiriRemote.apk");
        if (old.exists()) old.delete();

        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(
                Uri.parse(UPDATE_BASE + "AmiriRemote.apk?t=" + System.currentTimeMillis()));
        req.setTitle("آپدیت ریموت امیری");
        req.setMimeType("application/vnd.android.package-archive");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "AmiriRemote.apk");

        if (downloadDone == null) {
            downloadDone = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != downloadId) return;
                    Uri apk = dm.getUriForDownloadedFile(id);
                    if (apk == null) {
                        toast("دانلود نشد، دوباره امتحان کن");
                        return;
                    }
                    Intent install = new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(apk, "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(install);
                }
            };
            IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadDone, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(downloadDone, f);
            }
        }
        downloadId = dm.enqueue(req);
        toast("در حال دانلود آپدیت…");
    }

    // ---------- UI helpers ----------

    private LinearLayout page() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#EDEFF2"));
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = px(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        return root;
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#1F2329"));
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button actionButton(String text, String color, View.OnClickListener l) {
        Button b = plainButton(text, color, 14);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        return b;
    }

    private Button rawButton(String text, int code, int textSp) {
        Button b = plainButton(text, "#FFFFFF", textSp);
        if (code < 0) b.setAlpha(0.45f);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.setTranslationZ(-px(4));
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (code < 0) toast("کد «" + text + "» هنوز پیدا نشده");
                    else sendRaw(code);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.setTranslationZ(0);
                    return true;
            }
            return false;
        });
        return b;
    }

    private Button remoteButton(String text, int key, boolean repeat, String color, int textSp) {
        Button b = plainButton(text, color, textSp);
        final Runnable[] repeater = new Runnable[1];
        repeater[0] = () -> {
            send(key);
            handler.postDelayed(repeater[0], 140);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.setTranslationZ(-px(4));
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    send(key);
                    if (repeat) handler.postDelayed(repeater[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.setTranslationZ(0);
                    handler.removeCallbacks(repeater[0]);
                    return true;
            }
            return false;
        });
        return b;
    }

    private Button plainButton(String text, String color, int textSp) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(textSp);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setPadding(px(4), 0, px(4), 0);
        styleButton(b, color);
        return b;
    }

    /**
     * Light "soft UI" look: white pill buttons with a soft shadow; the orange
     * accent (or the red power button) gets a warm glowing fill.
     * Any other colour passed in is treated as a normal white button.
     */
    private void styleButton(Button b, String color) {
        boolean accent = color.equals("#F5A623") || color.equals("#4A6FD1");
        boolean danger = color.equals("#D32F2F") || color.equals("#5A2A2A");
        boolean green = color.equals("#2E9E4F");
        boolean blue = color.equals("#2B4C7E");
        int radius = px(26);

        GradientDrawable normal, pressed;
        if (green || blue) {
            int c1 = Color.parseColor(green ? "#4CC36A" : "#4A72B0");
            int c2 = Color.parseColor(green ? "#22863F" : "#22406E");
            normal = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
            pressed = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c2, c2});
            b.setTextColor(Color.WHITE);
        } else if (accent || danger) {
            int c1 = Color.parseColor(accent ? "#FFC247" : "#FF6B5E");
            int c2 = Color.parseColor(accent ? "#F29A0E" : "#E53935");
            normal = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
            pressed = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c2, c2});
            b.setTextColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 28) {
                int glow = Color.parseColor(accent ? "#F5A623" : "#E53935");
                b.setOutlineAmbientShadowColor(glow);
                b.setOutlineSpotShadowColor(glow);
            }
        } else {
            normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.parseColor("#FFFFFF"), Color.parseColor("#F6F7F9")});
            pressed = new GradientDrawable();
            pressed.setColor(Color.parseColor("#E6E8EC"));
            b.setTextColor(Color.parseColor("#2B2F36"));
            if (Build.VERSION.SDK_INT >= 28) {
                b.setOutlineAmbientShadowColor(Color.parseColor("#8A94A6"));
                b.setOutlineSpotShadowColor(Color.parseColor("#8A94A6"));
            }
        }
        normal.setCornerRadius(radius);
        pressed.setCornerRadius(radius);
        normal.setStroke(px(1), Color.parseColor(accent || danger || green || blue ? "#00FFFFFF" : "#FFFFFF"));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        b.setBackground(s);
        b.setElevation(px(accent || danger || green || blue ? 8 : 5));
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weighted(int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(heightDp), 1f);
        lp.setMargins(px(7), px(7), px(7), px(7));
        return lp;
    }

    private LinearLayout.LayoutParams weightedWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(px(4), 0, px(4), 0);
        return lp;
    }

    private LinearLayout.LayoutParams tall(int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(heightDp));
        lp.setMargins(px(3), px(7), px(3), px(7));
        return lp;
    }

    private View space(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(heightDp)));
        return v;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int px(int v) {
        return Math.round(v * dp);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (downloadDone != null) {
            try { unregisterReceiver(downloadDone); } catch (Exception ignored) { }
        }
        irThread.shutdownNow();
        super.onDestroy();
    }
}
