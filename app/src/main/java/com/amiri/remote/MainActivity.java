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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple IR remote for a Chinese "WISDOM·SHARE Smart cloud TV" board.
 * Uses the phone's IR blaster (ConsumerIrManager). Three code sets because
 * these boards ship with different remote headers:
 *   Set 1: NEC, header 0x00BF
 *   Set 2: NEC, header 0x20DF
 *   Set 3: Samsung32, header 0x0707
 */
public class MainActivity extends Activity {

    static final int POWER = 0, MUTE = 1, VOL_UP = 2, VOL_DN = 3, CH_UP = 4, CH_DN = 5,
            OK = 6, UP = 7, DOWN = 8, LEFT = 9, RIGHT = 10, HOME = 11, BACK = 12, MENU = 13;

    static final String[] SET_NAMES = {"کد ۱", "کد ۲", "کد ۳"};
    static final int[][] ADDR = {{0x00, 0xBF}, {0x20, 0xDF}, {0x07, 0x07}};
    static final boolean[] SAMSUNG = {false, false, true};
    static final int[][] CODES = {
            // POWER MUTE VOL+ VOL- CH+  CH-  OK   UP   DOWN LEFT RIGHT HOME BACK MENU
            {0x00, 0x01, 0x48, 0x49, 0x4A, 0x4B, 0x10, 0x13, 0x14, 0x11, 0x12, 0x64, 0x15, 0x40},
            {0x52, 0x53, 0x03, 0x41, 0x02, 0x09, 0x4A, 0x47, 0x4D, 0x49, 0x4B, 0x0F, 0x1E, 0x4E},
            {2, 15, 7, 11, 18, 16, 104, 96, 97, 101, 98, 121, 88, 26},
    };

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private int set = 0;
    private TextView status;
    private final Button[] setButtons = new Button[3];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService irThread = Executors.newSingleThreadExecutor();
    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        set = prefs.getInt("set", 0);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        getWindow().setStatusBarColor(Color.parseColor("#0F1626"));
        setContentView(buildUi());
        refreshSetButtons();
        if (ir == null || !ir.hasIrEmitter()) {
            status.setText("این گوشی فرستنده مادون‌قرمز (IR) ندارد");
            status.setTextColor(Color.parseColor("#FF7070"));
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

    private void send(int key) {
        if (ir == null || !ir.hasIrEmitter()) return;
        final int s = set;
        final int[] pattern = buildPattern(ADDR[s][0], ADDR[s][1], CODES[s][key], SAMSUNG[s]);
        irThread.execute(() -> {
            try {
                ir.transmit(38000, pattern);
            } catch (Exception e) {
                handler.post(() -> status.setText("خطا در ارسال: " + e.getMessage()));
            }
        });
    }

    // ---------- UI ----------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0F1626"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = px(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ریموت تلویزیون");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("گوشی را رو به تلویزیون بگیرید. اگر صدا کم و زیاد نشد، کد دیگری را امتحان کنید.");
        status.setTextColor(Color.parseColor("#9FB0D0"));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, px(6), 0, px(12));
        root.addView(status);

        // Code-set selector
        LinearLayout sets = row();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = plainButton(SET_NAMES[i], "#24314F", 15);
            b.setOnClickListener(v -> {
                set = idx;
                prefs.edit().putInt("set", idx).apply();
                refreshSetButtons();
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            });
            setButtons[i] = b;
            sets.addView(b, weighted(48));
        }
        root.addView(sets);
        root.addView(space(16));

        // Power
        LinearLayout powerRow = row();
        powerRow.addView(remoteButton("⏻  روشن / خاموش", POWER, false, "#D32F2F", 18), weighted(64));
        root.addView(powerRow);
        root.addView(space(16));

        // Volume / mute / channel
        LinearLayout mid = row();
        LinearLayout vol = column();
        vol.addView(remoteButton("صدا +", VOL_UP, true, "#2E4A7D", 18), tall(70));
        vol.addView(remoteButton("صدا −", VOL_DN, true, "#2E4A7D", 18), tall(70));
        LinearLayout muteCol = column();
        muteCol.setGravity(Gravity.CENTER);
        muteCol.addView(remoteButton("🔇\nبی‌صدا", MUTE, false, "#3A3F55", 14), tall(90));
        LinearLayout ch = column();
        ch.addView(remoteButton("کانال +", CH_UP, true, "#2E7D5B", 18), tall(70));
        ch.addView(remoteButton("کانال −", CH_DN, true, "#2E7D5B", 18), tall(70));
        mid.addView(vol, weighted(160));
        mid.addView(muteCol, weighted(160));
        mid.addView(ch, weighted(160));
        root.addView(mid);
        root.addView(space(18));

        // D-pad
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
        root.addView(space(16));

        // Back / Home / Menu
        LinearLayout bottom = row();
        bottom.addView(remoteButton("↩ برگشت", BACK, false, "#3A3F55", 15), weighted(54));
        bottom.addView(remoteButton("⌂ خانه", HOME, false, "#3A3F55", 15), weighted(54));
        bottom.addView(remoteButton("☰ منو", MENU, false, "#3A3F55", 15), weighted(54));
        root.addView(bottom);

        return scroll;
    }

    private void refreshSetButtons() {
        for (int i = 0; i < setButtons.length; i++) {
            boolean on = i == set;
            setButtons[i].setBackground(bg(on ? "#F5A623" : "#24314F"));
            setButtons[i].setTextColor(on ? Color.BLACK : Color.WHITE);
        }
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
        int r = Math.min(255, Color.red(c) + 50);
        int g = Math.min(255, Color.green(c) + 50);
        int b = Math.min(255, Color.blue(c) + 50);
        return Color.rgb(r, g, b);
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
        lp.setMargins(px(5), px(5), px(5), px(5));
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
