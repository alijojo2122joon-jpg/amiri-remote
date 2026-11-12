package com.amiri.remote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.ConsumerIrManager;
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
            {0x52, 0x53, 0x03, 0x41, 0x02, 0x09, 0x4A, 0x47, 0x4D, 0x49, 0x4B, 0x0F, 0x1E, 0x4E, 0x0B},
            {2, 15, 7, 11, 18, 16, 104, 96, 97, 101, 98, 121, 88, 26, 1},
            {0x45, 0x47, 0x15, 0x07, 0x09, 0x16, 0x1C, 0x18, 0x52, 0x08, 0x5A, 0x44, 0x43, 0x40, 0x46},
            {0x08, 0x09, 0x02, 0x03, 0x00, 0x01, 0x44, 0x40, 0x41, 0x07, 0x06, 0x7C, 0x28, 0x43, 0x0B},
    };

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private int set = 0;
    private TextView status;
    private final Button[] setButtons = new Button[SET_NAMES.length];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService irThread = Executors.newSingleThreadExecutor();
    private float dp;
    private boolean hasIr;

    // setup screen state
    private int scanCode = 0;
    private boolean scanning = false;
    private TextView codeView, codeInfo;
    private Button scanBtn;
    private final Runnable scanStep = new Runnable() {
        @Override
        public void run() {
            if (!scanning) return;
            scanCode = (scanCode + 1) & 0xFF;
            sendRaw(scanCode);
            updateCodeView();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        set = Math.min(prefs.getInt("set", 0), SET_NAMES.length - 1);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        hasIr = ir != null && ir.hasIrEmitter();
        getWindow().setStatusBarColor(Color.parseColor("#0F1626"));
        showRemote();
    }

    @Override
    public void onBackPressed() {
        if (codeView != null) {
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
        stopScan();
        codeView = null;
        codeInfo = null;
        scanBtn = null;

        LinearLayout root = page();

        root.addView(title("ریموت تلویزیون"));

        status = new TextView(this);
        status.setText(hasIr
                ? "اگر دکمه‌ای اشتباه کار کرد، پایین صفحه «⚙ تنظیم دکمه‌ها» را بزن."
                : "این گوشی فرستنده مادون‌قرمز (IR) ندارد");
        status.setTextColor(Color.parseColor(hasIr ? "#9FB0D0" : "#FF7070"));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, px(6), 0, px(12));
        root.addView(status);

        root.addView(setSelector());
        root.addView(space(14));

        LinearLayout powerRow = row();
        powerRow.addView(remoteButton("⏻  روشن / خاموش", POWER, false, "#D32F2F", 18), weighted(62));
        root.addView(powerRow);
        root.addView(space(12));

        LinearLayout mid = row();
        LinearLayout vol = column();
        vol.addView(remoteButton("صدا +", VOL_UP, true, "#2E4A7D", 18), tall(70));
        vol.addView(remoteButton("صدا −", VOL_DN, true, "#2E4A7D", 18), tall(70));
        LinearLayout centerCol = column();
        centerCol.addView(remoteButton("🔇 بی‌صدا", MUTE, false, "#3A3F55", 14), tall(70));
        centerCol.addView(remoteButton("⇄ ورودی", SOURCE, false, "#3A3F55", 14), tall(70));
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
        Button setup = plainButton("⚙  تنظیم دکمه‌ها", "#F5A623", 16);
        setup.setTextColor(Color.BLACK);
        setup.setOnClickListener(v -> showSetup());
        setupRow.addView(setup, weighted(52));
        root.addView(setupRow);
    }

    private View setSelector() {
        LinearLayout sets = row();
        for (int i = 0; i < SET_NAMES.length; i++) {
            final int idx = i;
            Button b = plainButton(SET_NAMES[i], "#24314F", 14);
            b.setOnClickListener(v -> {
                set = idx;
                prefs.edit().putInt("set", idx).apply();
                refreshSetButtons();
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (codeView != null) updateCodeView();
            });
            setButtons[i] = b;
            sets.addView(b, weighted(44));
        }
        refreshSetButtons();
        return sets;
    }

    private void refreshSetButtons() {
        for (int i = 0; i < setButtons.length; i++) {
            if (setButtons[i] == null) continue;
            boolean on = i == set;
            setButtons[i].setBackground(bg(on ? "#F5A623" : "#24314F"));
            setButtons[i].setTextColor(on ? Color.BLACK : Color.WHITE);
        }
    }

    // ---------- Setup screen ----------

    private void showSetup() {
        LinearLayout root = page();
        root.addView(title("تنظیم دکمه‌ها"));

        TextView help = new TextView(this);
        help.setText("۱) کدی را انتخاب کن که تلویزیون با آن واکنش نشان می‌دهد.\n"
                + "۲) با ◀ ▶ کدها را یکی‌یکی بفرست (یا اسکن خودکار).\n"
                + "۳) هر وقت تلویزیون کاری کرد، پایین بزن که آن کار کدام دکمه است.");
        help.setTextColor(Color.parseColor("#9FB0D0"));
        help.setTextSize(13);
        help.setPadding(0, px(6), 0, px(10));
        root.addView(help);

        root.addView(setSelector());
        root.addView(space(10));

        codeView = new TextView(this);
        codeView.setTextColor(Color.WHITE);
        codeView.setTextSize(38);
        codeView.setTypeface(Typeface.DEFAULT_BOLD);
        codeView.setGravity(Gravity.CENTER);
        root.addView(codeView);

        codeInfo = new TextView(this);
        codeInfo.setTextColor(Color.parseColor("#F5A623"));
        codeInfo.setTextSize(13);
        codeInfo.setGravity(Gravity.CENTER);
        codeInfo.setPadding(0, 0, 0, px(8));
        root.addView(codeInfo);

        LinearLayout nav = row();
        nav.addView(actionButton("−10", "#24314F", v -> step(-10)), weighted(54));
        nav.addView(actionButton("◀", "#24314F", v -> step(-1)), weighted(54));
        nav.addView(actionButton("📡 ارسال", "#4A6FD1", v -> sendRaw(scanCode)), weighted(54));
        nav.addView(actionButton("▶", "#24314F", v -> step(1)), weighted(54));
        nav.addView(actionButton("+10", "#24314F", v -> step(10)), weighted(54));
        root.addView(nav);

        LinearLayout scanRow = row();
        scanBtn = actionButton("▶ اسکن خودکار", "#2E7D5B", v -> toggleScan());
        scanRow.addView(scanBtn, weighted(50));
        root.addView(scanRow);

        TextView assignTitle = new TextView(this);
        assignTitle.setText("این کد را بده به دکمه:");
        assignTitle.setTextColor(Color.WHITE);
        assignTitle.setTextSize(15);
        assignTitle.setPadding(0, px(14), 0, px(4));
        root.addView(assignTitle);

        LinearLayout line = null;
        for (int k = 0; k < KEY_COUNT; k++) {
            if (k % 3 == 0) {
                line = row();
                root.addView(line);
            }
            final int key = k;
            line.addView(actionButton(KEY_NAMES[k], "#3A3F55", v -> assign(key)), weighted(50));
        }
        if (KEY_COUNT % 3 != 0) {
            for (int f = KEY_COUNT % 3; f < 3; f++) line.addView(new View(this), weighted(50));
        }

        root.addView(space(14));
        LinearLayout last = row();
        last.addView(actionButton("پیش‌فرض این کد", "#5A2A2A", v -> resetSet()), weighted(50));
        last.addView(actionButton("✔ برگشت به ریموت", "#F5A623", v -> showRemote()), weighted(50));
        root.addView(last);

        updateCodeView();
    }

    private void step(int d) {
        stopScan();
        scanCode = (scanCode + d + 256) & 0xFF;
        sendRaw(scanCode);
        updateCodeView();
    }

    private void toggleScan() {
        if (scanning) {
            stopScan();
            toast("اسکن متوقف شد. با ◀ ▶ و «ارسال» کد دقیق را پیدا کن.");
        } else {
            scanning = true;
            if (scanBtn != null) {
                scanBtn.setText("⏸ توقف اسکن");
                scanBtn.setBackground(bg("#C62828"));
            }
            sendRaw(scanCode);
            updateCodeView();
            handler.postDelayed(scanStep, 1500);
        }
    }

    private void stopScan() {
        scanning = false;
        handler.removeCallbacks(scanStep);
        if (scanBtn != null) {
            scanBtn.setText("▶ اسکن خودکار");
            scanBtn.setBackground(bg("#2E7D5B"));
        }
    }

    private void assign(int key) {
        stopScan();
        prefs.edit().putInt("s" + set + "_k" + key, scanCode).apply();
        toast("کد " + scanCode + " به «" + KEY_NAMES[key] + "» داده شد ✔");
        updateCodeView();
    }

    private void resetSet() {
        stopScan();
        SharedPreferences.Editor e = prefs.edit();
        for (int k = 0; k < KEY_COUNT; k++) e.remove("s" + set + "_k" + k);
        e.apply();
        toast("دکمه‌های " + SET_NAMES[set] + " به حالت اول برگشت");
        updateCodeView();
    }

    private void updateCodeView() {
        if (codeView == null) return;
        codeView.setText(String.valueOf(scanCode));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < KEY_COUNT; k++) {
            if (codeFor(k) == scanCode) {
                if (sb.length() > 0) sb.append("، ");
                sb.append(KEY_NAMES[k]);
            }
        }
        codeInfo.setText(sb.length() == 0 ? "(به هیچ دکمه‌ای وصل نیست)" : "الان مال: " + sb);
    }

    // ---------- UI helpers ----------

    private LinearLayout page() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F1626"));
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
        t.setTextColor(Color.WHITE);
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
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    send(key);
                    if (repeat) handler.postDelayed(repeater[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
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
        b.setTextColor(Color.WHITE);
        b.setBackground(bg(color));
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private StateListDrawable bg(String color) {
        int c = Color.parseColor(color);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(c);
        normal.setCornerRadius(px(16));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(lighten(c));
        pressed.setCornerRadius(px(16));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    private static int lighten(int c) {
        return Color.rgb(Math.min(255, Color.red(c) + 50),
                Math.min(255, Color.green(c) + 50),
                Math.min(255, Color.blue(c) + 50));
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
        lp.setMargins(px(4), px(4), px(4), px(4));
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
        lp.setMargins(0, px(4), 0, px(4));
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
        irThread.shutdownNow();
        super.onDestroy();
    }
}
