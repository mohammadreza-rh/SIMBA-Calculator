package com.example.desktopcalculator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(9, 14, 22));
        window.setNavigationBarColor(Color.rgb(9, 14, 22));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(0);
        }

        setTitle("SIMBA");
        setContentView(new CalculatorView(this));
    }

    static class CalculatorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SharedPreferences prefs;
        private final AudioManager audioManager;

        private static final int BG = Color.rgb(10, 15, 23);
        private static final int DARK_KEY = Color.rgb(16, 27, 40);
        private static final int NUMBER_KEY = Color.rgb(137, 147, 163);
        private static final int DISPLAY = Color.rgb(193, 207, 197);
        private static final int AC = Color.rgb(214, 55, 123);
        private static final int WHITE = Color.WHITE;

        private String display = "0";
        private BigDecimal accumulator = BigDecimal.ZERO;
        private String pendingOperator = null;
        private boolean newInput = true;
        private boolean error = false;
        private boolean justEvaluated = false;

        private BigDecimal memory = BigDecimal.ZERO;
        private boolean mrcArmed = false;
        private boolean soundOn = true;

        private final List<HistoryEntry> history = new ArrayList<>();
        private int historyCursor = -1;
        private HistoryEntry activeCheckEntry = null;
        private int activeCheckStep = 0;
        private OperationSession session = new OperationSession();
        private OperationSession lastReplay = null;

        private int insetTop = 0;
        private int insetBottom = 0;

        // Six-column layout. AUTO REPLAY is removed; CORRECT occupies its old space.
        // + and - are intentionally swapped relative to the earlier version.
        private final String[][] keys = {
                {"SOUND", "MRC", "M−", "M+", "CHECK←", "CHECK→"},
                {"%", "7", "8", "9", "CORRECT", "÷"},
                {"CE", "4", "5", "6", "×", "+"},
                {"AC", "1", "2", "3", "−", "="},
                {"0", "00", ".", "", "", "="}
        };

        CalculatorView(Context context) {
            super(context);
            prefs = context.getSharedPreferences("simba_prefs", Context.MODE_PRIVATE);
            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            soundOn = prefs.getBoolean("sound_on", true);
            loadMemory();
            loadHistory();
            setFocusable(true);
            setBackgroundColor(BG);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setOnApplyWindowInsetsListener((v, insets) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowInsets wi = insets;
                        insetTop = wi.getInsets(WindowInsets.Type.statusBars()).top;
                        insetBottom = wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    } else {
                        insetTop = insets.getSystemWindowInsetTop();
                        insetBottom = insets.getSystemWindowInsetBottom();
                    }
                    invalidate();
                    return insets;
                });
            }
        }

        private void clickSound() {
            if (!soundOn || audioManager == null) return;
            try {
                // Android's built-in key-click effect; avoids the unavailable TONE_PROP_CLICK constant.
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f);
            } catch (Exception ignored) {
                // Sound must never break calculator input.
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            final float w = getWidth();
            final float h = getHeight();
            final float top = insetTop;
            final float bottom = Math.max(top + 1, h - insetBottom);

            c.drawColor(BG);

            float margin = Math.max(14f, w * 0.035f);
            float gap = Math.max(6f, w * 0.012f);

            // Header
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setColor(WHITE);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(Math.min(30f, w * 0.075f));
            c.drawText("SIMBA", margin, top + 38, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(Math.min(16f, w * 0.043f));
            c.drawText("14 digits", w - margin, top + 35, paint);

            // Display: deliberately slightly shorter to give the keys more room.
            float displayTop = top + 52;
            float displayBottom = top + Math.min(330f, Math.max(245f, (bottom - top) * 0.245f));
            paint.setColor(DISPLAY);
            c.drawRoundRect(new RectF(margin, displayTop, w - margin, displayBottom), 15, 15, paint);

            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(Math.min(24f, w * 0.06f));
            if (memory.compareTo(BigDecimal.ZERO) != 0) c.drawText("M", margin + 20, displayTop + 40, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            if (pendingOperator != null) {
                paint.setTextSize(Math.min(28f, w * 0.065f));
                c.drawText(pendingOperator, w - margin - 20, displayTop + 40, paint);
            }

            String shown = display;
            if (shown.length() > 14) shown = shown.substring(Math.max(0, shown.length() - 14));
            paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
            paint.setTextSize(Math.min(58f, w * 0.145f));
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(shown, w - margin - 18, displayBottom - 28, paint);

            float gridTop = displayBottom + margin;
            float gridBottom = bottom - margin;
            float rowH = (gridBottom - gridTop - gap * 4) / 5f;
            float colW = (w - 2 * margin - gap * 5) / 6f;

            for (int r = 0; r < 5; r++) {
                for (int col = 0; col < 6; col++) {
                    String label = keys[r][col];
                    if (label.isEmpty()) continue;

                    float x = margin + col * (colW + gap);
                    float y = gridTop + r * (rowH + gap);
                    RectF rect = new RectF(x, y, x + colW, y + rowH);

                    // CORRECT spans two columns, replacing the removed AUTO REPLAY key.
                    if (r == 1 && col == 4) {
                        rect.right = x + colW * 2 + gap;
                        drawButton(c, rect, "CORRECT", DARK_KEY, WHITE);
                        continue;
                    }
                    if (r == 1 && col == 5) continue;

                    // '=' spans rows 4 and 5.
                    if (r == 3 && col == 5) {
                        rect.bottom = y + rowH * 2 + gap;
                        drawButton(c, rect, "=", DARK_KEY, WHITE);
                        continue;
                    }
                    if (r == 4 && col == 5) continue;

                    drawButton(c, rect, label, keyColor(label), WHITE);
                }
            }
        }

        private int keyColor(String label) {
            if ("AC".equals(label)) return AC;
            if (label.matches("\\d+") || ".".equals(label)) return NUMBER_KEY;
            return DARK_KEY;
        }

        private void drawButton(Canvas c, RectF rect, String label, int bg, int fg) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(115, 0, 0, 0));
            paint.setShadowLayer(5, 0, 3, Color.BLACK);
            c.drawRoundRect(rect, 13, 13, paint);
            paint.clearShadowLayer();

            paint.setColor(bg);
            c.drawRoundRect(new RectF(rect.left, rect.top, rect.right, rect.bottom - 2), 13, 13, paint);

            paint.setColor(fg);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));

            if ("SOUND".equals(label)) {
                drawSpeakerIcon(c, rect.centerX(), rect.centerY(), soundOn);
                return;
            }
            if ("CORRECT".equals(label)) {
                paint.setTextSize(Math.min(18f, getWidth() * 0.045f));
                c.drawText("CORRECT", rect.centerX(), rect.centerY() - 8, paint);
                paint.setTextSize(Math.min(15f, getWidth() * 0.038f));
                c.drawText("BACKSPACE", rect.centerX(), rect.centerY() + 18, paint);
                return;
            }
            if ("CHECK←".equals(label) || "CHECK→".equals(label)) {
                paint.setTextSize(Math.min(16f, getWidth() * 0.04f));
                c.drawText("CHECK", rect.centerX(), rect.centerY() - 7, paint);
                paint.setTextSize(Math.min(27f, getWidth() * 0.066f));
                c.drawText(label.endsWith("←") ? "←" : "→", rect.centerX(), rect.centerY() + 23, paint);
                return;
            }
            if ("MRC".equals(label) || "M+".equals(label) || "M−".equals(label)) {
                paint.setTextSize(Math.min(22f, getWidth() * 0.055f));
            } else if (label.matches("\\d+") || ".".equals(label)) {
                paint.setTextSize(Math.min(42f, getWidth() * 0.105f));
            } else if ("=".equals(label) || "+".equals(label) || "−".equals(label) || "×".equals(label) || "÷".equals(label) || "%".equals(label) || "CE".equals(label) || "AC".equals(label)) {
                paint.setTextSize(Math.min(34f, getWidth() * 0.085f));
            } else {
                paint.setTextSize(Math.min(28f, getWidth() * 0.07f));
            }

            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(label, rect.centerX(), baseline, paint);
        }

        private void drawSpeakerIcon(Canvas c, float cx, float cy, boolean on) {
            paint.setColor(WHITE);
            Path p = new Path();
            p.moveTo(cx - 19, cy - 7);
            p.lineTo(cx - 10, cy - 7);
            p.lineTo(cx + 2, cy - 17);
            p.lineTo(cx + 2, cy + 17);
            p.lineTo(cx - 10, cy + 7);
            p.lineTo(cx - 19, cy + 7);
            p.close();
            c.drawPath(p, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            RectF arc = new RectF(cx - 4, cy - 14, cx + 22, cy + 14);
            c.drawArc(arc, -55, 110, false, paint);
            if (!on) {
                c.drawLine(cx + 7, cy - 12, cx + 23, cy + 12, paint);
                c.drawLine(cx + 23, cy - 12, cx + 7, cy + 12, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;

            float x = e.getX(), y = e.getY();
            float w = getWidth(), h = getHeight();
            float margin = Math.max(14f, w * 0.035f);
            float gap = Math.max(6f, w * 0.012f);
            float top = insetTop;
            float bottom = Math.max(top + 1, h - insetBottom);
            float displayTop = top + 52;
            float displayBottom = top + Math.min(330f, Math.max(245f, (bottom - top) * 0.245f));

            if (y >= displayTop && y <= displayBottom) {
                showHistory();
                return true;
            }

            float gridTop = displayBottom + margin;
            float gridBottom = bottom - margin;
            float rowH = (gridBottom - gridTop - gap * 4) / 5f;
            float colW = (w - 2 * margin - gap * 5) / 6f;
            if (y < gridTop || y > gridBottom) return true;

            int row = (int) ((y - gridTop) / (rowH + gap));
            int col = (int) ((x - margin) / (colW + gap));
            if (row < 0 || row >= 5 || col < 0 || col >= 6) return true;

            // CORRECT spans columns 5 and 6 on row 2.
            if (row == 1 && (col == 4 || col == 5)) {
                handleKey("CORRECT");
                invalidate();
                return true;
            }
            // '=' spans rows 4 and 5 in column 6.
            if (col == 5 && (row == 3 || row == 4)) {
                handleKey("=");
                invalidate();
                return true;
            }

            String key = keys[row][col];
            if (key.isEmpty()) return true;
            handleKey(key);
            invalidate();
            return true;
        }

        private void handleKey(String key) {
            clickSound();

            switch (key) {
                case "SOUND":
                    soundOn = !soundOn;
                    prefs.edit().putBoolean("sound_on", soundOn).apply();
                    return;
                case "MRC":
                    if (mrcArmed) {
                        memory = BigDecimal.ZERO;
                        mrcArmed = false;
                        saveMemory();
                    } else {
                        display = format(memory);
                        newInput = true;
                        mrcArmed = true;
                    }
                    return;
                case "M+":
                    memory = memory.add(valueOfDisplay());
                    mrcArmed = false;
                    saveMemory();
                    return;
                case "M−":
                    memory = memory.subtract(valueOfDisplay());
                    mrcArmed = false;
                    saveMemory();
                    return;
                case "CHECK←":
                    checkMove(-1);
                    return;
                case "CHECK→":
                    checkMove(1);
                    return;
                case "CORRECT":
                    correctBackspace();
                    return;
                case "AC":
                    clearAll();
                    return;
                case "CE":
                    display = "0";
                    newInput = true;
                    return;
                case "%":
                    percent();
                    return;
                case ".":
                    enterDecimal();
                    return;
                case "=":
                    equals();
                    return;
                case "+":
                case "−":
                case "×":
                case "÷":
                    operator(key);
                    return;
                default:
                    if (key.matches("\\d+")) enterDigits(key);
            }
        }

        private void enterDigits(String key) {
            if (error) return;
            if (justEvaluated) {
                startNewSession();
                display = "0";
                justEvaluated = false;
            }
            if (newInput || "0".equals(display)) {
                display = key;
                newInput = false;
            } else if (digitsOnly(display).length() < 14) {
                String next = display + key;
                if (digitsOnly(next).length() <= 14) display = next;
            }
            historyCursor = -1;
        }

        private void enterDecimal() {
            if (error) return;
            if (justEvaluated) {
                startNewSession();
                display = "0";
                justEvaluated = false;
            }
            if (newInput) {
                display = "0.";
                newInput = false;
            } else if (!display.contains(".")) {
                display += ".";
            }
            historyCursor = -1;
        }

        private void correctBackspace() {
            if (error) {
                clearAll();
                return;
            }
            if (newInput) return;
            if (display.length() <= 1 || (display.length() == 2 && display.startsWith("-"))) {
                display = "0";
                newInput = true;
                return;
            }
            display = display.substring(0, display.length() - 1);
            if (display.isEmpty() || "-".equals(display)) {
                display = "0";
                newInput = true;
            }
        }

        private void operator(String op) {
            if (error) return;
            if (pendingOperator == null) {
                accumulator = valueOfDisplay();
                session.startIfNeeded(format(valueOfDisplay()));
            } else if (!newInput) {
                if (!apply(valueOfDisplay())) return;
            }
            pendingOperator = op;
            newInput = true;
            justEvaluated = false;
            session.addOperator(op, format(accumulator));
        }

        private boolean apply(BigDecimal rhs) {
            BigDecimal before = accumulator;
            try {
                switch (pendingOperator) {
                    case "+": accumulator = accumulator.add(rhs); break;
                    case "−": accumulator = accumulator.subtract(rhs); break;
                    case "×": accumulator = accumulator.multiply(rhs); break;
                    case "÷":
                        if (rhs.compareTo(BigDecimal.ZERO) == 0) {
                            display = "Error";
                            error = true;
                            return false;
                        }
                        accumulator = accumulator.divide(rhs, 10, RoundingMode.HALF_UP);
                        break;
                }
            } catch (ArithmeticException ex) {
                display = "Error";
                error = true;
                return false;
            }
            display = format(accumulator);
            session.addStep(format(before), pendingOperator, format(rhs), display);
            return true;
        }

        private void equals() {
            if (error || pendingOperator == null) return;
            if (!newInput) {
                if (!apply(valueOfDisplay())) return;
            }
            String result = format(accumulator);
            display = result;
            String expression = session.expressionWithResult(result);
            addHistory(new HistoryEntry(expression, new ArrayList<>(session.steps), result));
            lastReplay = session.copy();
            session = new OperationSession();
            pendingOperator = null;
            newInput = true;
            justEvaluated = true;
            historyCursor = -1;
        }

        private void percent() {
            if (error) return;
            BigDecimal shown = valueOfDisplay();
            BigDecimal result;
            if (pendingOperator == null) {
                result = shown.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            } else if ("+".equals(pendingOperator) || "−".equals(pendingOperator)) {
                result = accumulator.multiply(shown).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            } else {
                result = shown.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            }
            display = format(result);
            // The percentage result is the RHS for the pending operator, so = must apply it.
            newInput = false;
        }

        private void checkMove(int direction) {
            if (history.isEmpty()) return;

            if (activeCheckEntry == null) {
                historyCursor = history.size() - 1;
                activeCheckEntry = history.get(historyCursor);
                if (activeCheckEntry.steps.isEmpty()) {
                    activeCheckStep = 0;
                } else {
                    activeCheckStep = direction < 0 ? activeCheckEntry.steps.size() - 1 : 0;
                }
            } else if (!activeCheckEntry.steps.isEmpty()) {
                int next = activeCheckStep + direction;
                if (next < 0) {
                    if (historyCursor > 0) {
                        historyCursor--;
                        activeCheckEntry = history.get(historyCursor);
                        activeCheckStep = Math.max(0, activeCheckEntry.steps.size() - 1);
                    } else {
                        activeCheckStep = 0;
                    }
                } else if (next >= activeCheckEntry.steps.size()) {
                    if (historyCursor < history.size() - 1) {
                        historyCursor++;
                        activeCheckEntry = history.get(historyCursor);
                        activeCheckStep = 0;
                    } else {
                        activeCheckStep = activeCheckEntry.steps.size() - 1;
                    }
                } else {
                    activeCheckStep = next;
                }
            }

            if (!activeCheckEntry.steps.isEmpty()) {
                display = activeCheckEntry.steps.get(activeCheckStep).result;
            } else {
                display = activeCheckEntry.result;
            }
            newInput = true;
            justEvaluated = false;
        }

        private void showHistory() {
            AlertDialog.Builder b = new AlertDialog.Builder(getContext());
            b.setTitle("SIMBA • History (50)");
            TextView tv = new TextView(getContext());
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(18);
            tv.setPadding(28, 22, 28, 22);
            tv.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            tv.setBackgroundColor(Color.rgb(22, 27, 36));

            if (history.isEmpty()) {
                tv.setText("No calculations yet.");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = history.size() - 1; i >= 0; i--) {
                    HistoryEntry e = history.get(i);
                    sb.append(String.format(Locale.US, "%02d  %s%n", history.size() - i, e.expression));
                    for (Step s : e.steps) {
                        sb.append("    ").append(s.left).append(' ').append(s.operator).append(' ').append(s.right)
                                .append(" = ").append(s.result).append('\n');
                    }
                    sb.append('\n');
                }
                tv.setText(sb.toString());
            }

            ScrollView scroll = new ScrollView(getContext());
            scroll.setBackgroundColor(Color.rgb(22, 27, 36));
            scroll.addView(tv);
            b.setView(scroll);
            b.setPositiveButton("Close", null);
            b.show();
        }

        private void replayLast() {
            // AUTO REPLAY is intentionally removed from the UI.
            // Replay data is retained internally only so the next version can add it without changing state format.
        }

        private void clearAll() {
            display = "0";
            accumulator = BigDecimal.ZERO;
            pendingOperator = null;
            newInput = true;
            error = false;
            justEvaluated = false;
            mrcArmed = false;
            session = new OperationSession();
            activeCheckEntry = null;
            historyCursor = -1;
        }

        private void startNewSession() {
            accumulator = BigDecimal.ZERO;
            pendingOperator = null;
            session = new OperationSession();
            error = false;
        }

        private BigDecimal valueOfDisplay() {
            try {
                return new BigDecimal(display);
            } catch (Exception e) {
                return BigDecimal.ZERO;
            }
        }

        private String digitsOnly(String s) {
            return s.replace("-", "").replace(".", "");
        }

        private String format(BigDecimal n) {
            if (n == null) return "0";
            try {
                return n.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            } catch (Exception e) {
                return "Error";
            }
        }

        private void addHistory(HistoryEntry entry) {
            history.add(entry);
            while (history.size() > 50) history.remove(0);
            activeCheckEntry = null;
            historyCursor = -1;
            saveHistory();
        }

        private void saveHistory() {
            // Compact text format: expression|result|step1;step2;...
            StringBuilder sb = new StringBuilder();
            for (HistoryEntry e : history) {
                sb.append(escape(e.expression)).append('|').append(escape(e.result)).append('|');
                for (Step s : e.steps) {
                    sb.append(escape(s.left)).append('~').append(escape(s.operator)).append('~')
                            .append(escape(s.right)).append('~').append(escape(s.result)).append(';');
                }
                sb.append('\n');
            }
            prefs.edit().putString("history_v2", sb.toString()).apply();
        }

        private void loadHistory() {
            String saved = prefs.getString("history_v2", "");
            if (saved.isEmpty()) return;
            String[] lines = saved.split("\\n");
            for (String line : lines) {
                try {
                    String[] p = line.split("\\|", -1);
                    if (p.length < 2) continue;
                    HistoryEntry e = new HistoryEntry(unescape(p[0]), new ArrayList<>(), unescape(p[1]));
                    if (p.length >= 3 && !p[2].isEmpty()) {
                        String[] steps = p[2].split(";", -1);
                        for (String st : steps) {
                            if (st.isEmpty()) continue;
                            String[] a = st.split("~", -1);
                            if (a.length == 4) e.steps.add(new Step(unescape(a[0]), unescape(a[1]), unescape(a[2]), unescape(a[3])));
                        }
                    }
                    history.add(e);
                } catch (Exception ignored) {}
            }
            while (history.size() > 50) history.remove(0);
        }

        private String escape(String s) {
            return s.replace("\\", "\\\\").replace("|", "\\p").replace("~", "\\t").replace(";", "\\s").replace("\n", "\\n");
        }

        private String unescape(String s) {
            return s.replace("\\n", "\n").replace("\\s", ";").replace("\\t", "~").replace("\\p", "|").replace("\\\\", "\\");
        }

        private void saveMemory() {
            prefs.edit().putString("memory_v2", memory.toPlainString()).apply();
        }

        private void loadMemory() {
            String value = prefs.getString("memory_v2", "0");
            try { memory = new BigDecimal(value); } catch (Exception e) { memory = BigDecimal.ZERO; }
        }

        static class Step {
            final String left, operator, right, result;
            Step(String left, String operator, String right, String result) {
                this.left = left; this.operator = operator; this.right = right; this.result = result;
            }
        }

        static class HistoryEntry {
            final String expression;
            final List<Step> steps;
            final String result;
            HistoryEntry(String expression, List<Step> steps, String result) {
                this.expression = expression; this.steps = steps; this.result = result;
            }
        }

        static class OperationSession {
            final List<Step> steps = new ArrayList<>();
            String first = null;
            String lastOperator = null;

            void startIfNeeded(String value) {
                if (first == null) first = value;
            }

            void addOperator(String op, String accumulatorValue) {
                lastOperator = op;
            }

            void addStep(String left, String operator, String right, String result) {
                steps.add(new Step(left, operator, right, result));
            }

            String expressionWithResult(String result) {
                if (steps.isEmpty()) return result + " = " + result;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < steps.size(); i++) {
                    Step s = steps.get(i);
                    if (i > 0) sb.append("  ");
                    sb.append(s.left).append(' ').append(s.operator).append(' ').append(s.right).append(" = ").append(s.result);
                }
                return sb.toString();
            }

            OperationSession copy() {
                OperationSession c = new OperationSession();
                c.first = first;
                c.lastOperator = lastOperator;
                c.steps.addAll(steps);
                return c;
            }
        }
    }
}
