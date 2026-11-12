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
            SOURCE = 14;
    static final int KEY_COUNT = 15;

    static final String[] KEY_NAMES = {
            "روشن/خاموش", "بی‌صدا", "صدا +", "صدا −", "کانال +", "کانال −",
            "OK", "بالا ▲", "پایین ▼", "چپ ◀", "راست ▶", "خانه", "برگشت", "منو", "ورودی (Source)"};

    static final String[] SET_NAMES = {"کد ۱", "کد ۲", "کد ۳", "کد ۴", "کد ۵"};
    static final int[][] ADDR = {{0x00, 0xBF}, {0x20, 0xDF}, {0x07, 0x07}, {0x00, 0xFF}, {0x04, 0xFB}};
    static final boolean[] SAMSUNG = {false, false, true, false, false};
    static final int[][] CODES = {
            // POWER MUTE VOL+ VOL- CH+  CH-  OK   UP   DOWN LEFT RIGHT HOME BACK MENU SOURCE
            {0x00, 0x01, 0x48, 0x49, 0x4A, 0x4B, 0x10, 0x13, 0x14, 0x11, 0x12, 0x64, 0x15, 0x40, 0x41},
            {0x0B, 0x53, 0x03, 0x41, 0x02, 0x09, 0x4A, 0x47, 0x4D, 0x49, 0x4B, 0x0F, 0x1E, 0x4E, 0x52},
            {2, 15, 7, 11, 18, 16, 104, 96, 97, 101, 98, 121, 88, 26, 1},
            {0x45, 0x47, 0x15, 0x07, 0x09, 0x16, 0x1C, 0x18, 0x52, 0x08, 0x5A, 0x44, 0x43, 0x40, 0x46},
            {0x08, 0x09, 0x02, 0x03, 0x00, 0x01, 0x44, 0x40, 0x41, 0x07, 0x06, 0x7C, 0x28, 0x43, 0x0B},
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
        showRemote();
    }

    @Override
    public void onBackPressed() {
        if (wizTitle != null) {
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
        sendRaw(codeFor(key));
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
        wizTitle = null;

        LinearLayout root = page();

        root.addView(title("ریموت تلویزیون"));

        status = new TextView(this);
        status.setText(hasIr
                ? "اگر دکمه‌ای اشتباه کار کرد، پایین صفحه «⚙ تنظیم دقیق دکمه‌ها» را بزن."
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

        LinearLayout powerRow = row();
        powerRow.addView(remoteButton("⏻  روشن / خاموش", POWER, false, "#D32F2F", 18), weighted(62));
        root.addView(powerRow);
        root.addView(space(12));

        LinearLayout mid = row();
        LinearLayout vol = column();
        vol.addView(remoteButton("صدا +", VOL_UP, true, "#2E4A7D", 18), tall(70));
        vol.addView(remoteButton("صدا −", VOL_DN, true, "#2E4A7D", 18), tall(70));
        LinearLayout centerCol = column();
        centerCol.addView(remoteButton("🔇\nبی‌صدا", MUTE, false, "#3A3F55", 15), tall(154));
        LinearLayout ch = column();
        ch.addView(remoteButton("کانال +", CH_UP, true, "#2E7D5B", 18), tall(70));
        ch.addView(remoteButton("کانال −", CH_DN, true, "#2E7D5B", 18), tall(70));
        mid.addView(vol, weightedWrap());
        mid.addView(centerCol, weightedWrap());
        mid.addView(ch, weightedWrap());
        root.addView(mid);
        root.addView(space(14));

        LinearLayout r1 = row();
        r1.addView(new View(this), weighted(64));
        r1.addView(remoteButton("▲", UP, true, "#24314F", 22), weighted(64));
        r1.addView(new View(this), weighted(64));
        LinearLayout r2 = row();
        r2.addView(remoteButton("◀", LEFT, true, "#24314F", 22), weighted(64));
        r2.addView(remoteButton("OK", OK, false, "#4A6FD1", 20), weighted(64));
        r2.addView(remoteButton("▶", RIGHT, true, "#24314F", 22), weighted(64));
        LinearLayout r3 = row();
        r3.addView(new View(this), weighted(64));
        r3.addView(remoteButton("▼", DOWN, true, "#24314F", 22), weighted(64));
        r3.addView(new View(this), weighted(64));
        root.addView(r1);
        root.addView(r2);
        root.addView(r3);
        root.addView(space(12));

        LinearLayout bottom = row();
        bottom.addView(remoteButton("↩ برگشت", BACK, false, "#3A3F55", 15), weighted(54));
        bottom.addView(remoteButton("⌂ خانه", HOME, false, "#3A3F55", 15), weighted(54));
        bottom.addView(remoteButton("☰ منو", MENU, false, "#3A3F55", 15), weighted(54));
        root.addView(bottom);
        root.addView(space(18));

        LinearLayout setupRow = row();
        Button setup = plainButton("⚙  تنظیم دقیق دکمه‌ها", "#F5A623", 16);
                setup.setOnClickListener(v -> showSetup());
        setupRow.addView(setup, weighted(52));
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

    // ---------- Setup wizard ----------

    // Order the wizard walks through (power last, so wrong guesses don't turn the TV off early).
    static final int[] WIZARD_ORDER = {VOL_UP, VOL_DN, MUTE, CH_UP, CH_DN, UP, DOWN, LEFT, RIGHT,
            OK, BACK, HOME, MENU, POWER};
    static final String[] EXPECT = {
            "تلویزیون باید خاموش (یا روشن) شود",
            "صدا باید قطع یا وصل شود",
            "صدا باید زیاد شود",
            "صدا باید کم شود",
            "کانال باید یکی جلو برود",
            "کانال باید یکی عقب برود",
            "باید گزینه انتخاب شود (OK)",
            "انتخاب در منو باید بالا برود",
            "انتخاب در منو باید پایین برود",
            "انتخاب در منو باید به چپ برود",
            "انتخاب در منو باید به راست برود",
            "باید صفحه اصلی (خانه) باز شود",
            "باید یک صفحه برگردد",
            "باید منو باز شود",
            "باید منوی ورودی‌ها (HDMI / AV / TV) باز شود"};

    private int wizStep = 0;
    private int[] candidates;
    private int candIdx = 0;
    private TextView wizTitle, wizExpect, wizProgress, wizCode;

    private void showSetup() {
        wizStep = 0;
        LinearLayout root = page();
        root.addView(title("تنظیم دقیق دکمه‌ها"));

        TextView help = new TextView(this);
        help.setText("گوشی را رو به تلویزیون بگیر. برای هر دکمه یک کد فرستاده می‌شود.\n"
                + "اگر تلویزیون همان کار درست را کرد «✔ درست شد» بزن،\n"
                + "وگرنه «✘ نه، بعدی» بزن تا کد بعدی امتحان شود.");
        help.setTextColor(Color.parseColor("#8A9099"));
        help.setTextSize(13);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, px(6), 0, px(14));
        root.addView(help);

        wizProgress = new TextView(this);
        wizProgress.setTextColor(Color.parseColor("#E8920C"));
        wizProgress.setTextSize(13);
        wizProgress.setGravity(Gravity.CENTER);
        root.addView(wizProgress);

        wizTitle = new TextView(this);
        wizTitle.setTextColor(Color.parseColor("#1F2329"));
        wizTitle.setTextSize(34);
        wizTitle.setTypeface(Typeface.DEFAULT_BOLD);
        wizTitle.setGravity(Gravity.CENTER);
        root.addView(wizTitle);

        wizExpect = new TextView(this);
        wizExpect.setTextColor(Color.parseColor("#2B2F36"));
        wizExpect.setTextSize(15);
        wizExpect.setGravity(Gravity.CENTER);
        root.addView(wizExpect);

        wizCode = new TextView(this);
        wizCode.setTextColor(Color.parseColor("#8A9099"));
        wizCode.setTextSize(12);
        wizCode.setGravity(Gravity.CENTER);
        wizCode.setPadding(0, px(4), 0, px(12));
        root.addView(wizCode);

        LinearLayout yes = row();
        yes.addView(actionButton("✔  درست شد", "#F5A623", v -> confirmStep()), weighted(64));
        root.addView(yes);

        LinearLayout no = row();
        no.addView(actionButton("✘  نه، بعدی", "#FFFFFF", v -> nextCandidate(1)), weighted(60));
        root.addView(no);

        LinearLayout more = row();
        more.addView(actionButton("◀ کد قبلی", "#FFFFFF", v -> nextCandidate(-1)), weighted(52));
        more.addView(actionButton("📡 دوباره بفرست", "#FFFFFF", v -> sendRaw(candidates[candIdx])), weighted(52));
        root.addView(more);

        LinearLayout nav = row();
        nav.addView(actionButton("دکمه قبلی", "#FFFFFF", v -> goStep(wizStep - 1)), weighted(50));
        nav.addView(actionButton("رد شو ⏭", "#FFFFFF", v -> goStep(wizStep + 1)), weighted(50));
        root.addView(nav);

        root.addView(space(14));
        LinearLayout last = row();
        last.addView(actionButton("پاک کردن همه", "#D32F2F", v -> resetSet()), weighted(50));
        last.addView(actionButton("برگشت به ریموت", "#FFFFFF", v -> showRemote()), weighted(50));
        root.addView(last);

        goStep(0);
    }

    private void goStep(int step) {
        if (step < 0) step = 0;
        if (step >= WIZARD_ORDER.length) {
            toast("همه دکمه‌ها تنظیم شد ✔");
            showRemote();
            return;
        }
        wizStep = step;
        int key = WIZARD_ORDER[step];
        candidates = buildCandidates(key);
        candIdx = 0;
        wizProgress.setText("دکمه " + (step + 1) + " از " + WIZARD_ORDER.length
                + (prefs.getBoolean("ok_k" + key, false) ? "  (قبلاً تأیید شده)" : ""));
        wizTitle.setText(KEY_NAMES[key]);
        wizExpect.setText(EXPECT[key]);
        if (key == POWER) {
            wizExpect.setText(EXPECT[key] + "\n(اگر خاموش شد، «✔» بزن و با همین دکمه روشنش کن)");
        }
        sendCandidate();
    }

    /** Current code first, then the codes from every known table, then all other codes. */
    private int[] buildCandidates(int key) {
        java.util.LinkedHashSet<Integer> list = new java.util.LinkedHashSet<>();
        java.util.HashSet<Integer> taken = new java.util.HashSet<>();
        for (int k = 0; k < KEY_COUNT; k++) {
            if (k != key && prefs.getBoolean("ok_k" + k, false)) taken.add(codeFor(k));
        }
        list.add(codeFor(key));
        list.add(CODES[1][key]);
        for (int[] row : CODES) list.add(row[key]);
        for (int[] row : CODES) for (int c : row) list.add(c);
        for (int c = 0; c < 256; c++) list.add(c);
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (int c : list) if (!taken.contains(c) || c == codeFor(key)) out.add(c);
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private void nextCandidate(int d) {
        candIdx = (candIdx + d + candidates.length) % candidates.length;
        sendCandidate();
    }

    private void sendCandidate() {
        int c = candidates[candIdx];
        wizCode.setText("کد " + c + "   •   امتحان " + (candIdx + 1) + " از " + candidates.length);
        sendRaw(c);
    }

    private void confirmStep() {
        int key = WIZARD_ORDER[wizStep];
        prefs.edit()
                .putInt("s" + set + "_k" + key, candidates[candIdx])
                .putBoolean("ok_k" + key, true)
                .apply();
        toast("«" + KEY_NAMES[key] + "» ذخیره شد ✔");
        goStep(wizStep + 1);
    }

    private void resetSet() {
        SharedPreferences.Editor e = prefs.edit();
        for (int k = 0; k < KEY_COUNT; k++) {
            e.remove("s" + set + "_k" + k);
            e.remove("ok_k" + k);
        }
        e.apply();
        toast("همه دکمه‌ها به حالت اول برگشت");
        goStep(0);
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
        int radius = px(26);

        GradientDrawable normal, pressed;
        if (accent || danger) {
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
        normal.setStroke(px(1), Color.parseColor(accent || danger ? "#00FFFFFF" : "#FFFFFF"));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        b.setBackground(s);
        b.setElevation(px(accent || danger ? 8 : 5));
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
