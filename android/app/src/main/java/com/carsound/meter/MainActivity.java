package com.carsound.meter;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int BG = 0xFFF3F3F1, CARD = 0xFFFFFFFF, INK = 0xFF15171A, MUT = 0xFF5F646B, LINE = 0xFFDEDCD7, ACC = 0xFFFF5A4E;
    static final int OK = 0xFF1D7A4A, WARN = 0xFFB7791F;

    private ScrollView sv;
    private LinearLayout resultCard, diagList;
    private FrView chart;
    private EqView eqView;
    private TextView score, grade, count, status, legend, details, eqNote, info, diagStatus, shareNote, prefHint;
    private Button run, reset, diagBtn, shareBtn;
    private RadioButton modeLr;
    private final List<Dsp.Meas> session = new ArrayList<>();
    private boolean busy;
    private Dsp.Result lastResult;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float dp;
    private Dsp.Pref pref = Dsp.PRESETS[0];
    private int presetIdx = 0;
    private boolean syncing;
    private final List<Button> chips = new ArrayList<>();
    private final SeekBar[] sliders = new SeekBar[4];
    private final TextView[] sliderVals = new TextView[4];
    private SharedPreferences store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        store = getSharedPreferences("taste", MODE_PRIVATE);
        loadPref();
        AudioEngine.chosenSource = store.getInt("source", -1);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = px(16);
        root.setPadding(pad, pad, pad, px(32));
        root.setBackgroundColor(BG);

        root.addView(text("차량 사운드 측정기", 28, INK, true));
        root.addView(text("① 입력 진단으로 휴대폰 마이크를 점검한 뒤 ② 측정하세요.", 14, MUT, false));

        // ---------- 1단계: 입력 진단 ----------
        LinearLayout s1 = card(root);
        s1.addView(stepHeader("1", "입력 진단"));
        s1.addView(text("휴대폰이 쓸 수 있는 녹음 방식 5가지로 같은 신호를 녹음해, 가장 정확한 방식을 자동으로 고릅니다. 처음 한 번만 하면 됩니다.", 14, MUT, false));
        s1.addView(text("차에서 평소 듣는 볼륨으로, 휴대폰을 운전석 귀 높이에 두고 움직이지 마세요.", 14, MUT, false));
        diagStatus = text("", 15, INK, true);
        diagStatus.setPadding(0, px(10), 0, 0);
        s1.addView(diagStatus);
        diagBtn = button("", true);
        diagBtn.setOnClickListener(v -> onDiag());
        s1.addView(diagBtn, lp(px(52), px(10)));
        diagList = new LinearLayout(this);
        diagList.setOrientation(LinearLayout.VERTICAL);
        s1.addView(diagList);

        // ---------- 2단계: 측정 ----------
        LinearLayout s2 = card(root);
        s2.addView(stepHeader("2", "측정"));
        s2.addView(text("머리 주변 위치를 조금씩 옮겨 3~5번 측정하면 자동으로 평균을 냅니다.", 14, MUT, false));
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        rg.setPadding(0, px(6), 0, 0);
        RadioButton st = new RadioButton(this);
        st.setText("스테레오 (약 11초)");
        st.setId(View.generateViewId());
        modeLr = new RadioButton(this);
        modeLr.setText("좌·우 분리 (약 21초)");
        modeLr.setId(View.generateViewId());
        rg.addView(st);
        rg.addView(modeLr);
        st.setChecked(true);
        rg.setOnCheckedChangeListener((g, id) -> {
            if (!busy && !session.isEmpty()) { session.clear(); clearResult("측정 방식을 바꿔서 이전 측정을 지웠습니다."); }
        });
        s2.addView(rg);
        run = button("", true);
        run.setOnClickListener(v -> onRun());
        s2.addView(run, lp(px(56), px(10)));
        reset = button("새로 시작 (평균 지우기)", false);
        reset.setOnClickListener(v -> { if (!busy) { session.clear(); clearResult("측정 기록을 지웠습니다."); } });
        s2.addView(reset, lp(px(46), px(8)));
        status = text("", 15, INK, true);
        status.setPadding(0, px(10), 0, 0);
        s2.addView(status);

        // ---------- 결과 ----------
        resultCard = card(root);
        resultCard.addView(text("측정 결과", 18, INK, true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(4), 0, px(6));
        score = text("--", 56, INK, true);
        row.addView(score);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(14), 0, 0, 0);
        grade = text("측정 전", 18, INK, true);
        count = text("2단계 측정을 하면 표시됩니다", 13, MUT, false);
        col.addView(grade);
        col.addView(count);
        row.addView(col);
        resultCard.addView(row);
        chart = new FrView(this);
        resultCard.addView(chart);
        legend = text("", 13, MUT, false);
        resultCard.addView(legend);

        buildTasteCard(root);

        LinearLayout c4 = card(root);
        c4.addView(text("세부 결과", 18, INK, true));
        details = text("측정 후 표시됩니다.", 15, INK, false);
        details.setLineSpacing(0, 1.3f);
        c4.addView(details);

        LinearLayout c5 = card(root);
        c5.addView(text("EQ 조정 제안", 18, INK, true));
        c5.addView(text("차량 이퀄라이저에서 각 대역을 막대만큼 올리거나 내려 보세요. 대역 이름이 조금 달라도 가장 가까운 값을 쓰면 됩니다.", 13, MUT, false));
        eqView = new EqView(this);
        LinearLayout.LayoutParams eqLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eqLp.topMargin = px(12);
        c5.addView(eqView, eqLp);
        eqNote = text("", 13, MUT, false);
        eqNote.setLineSpacing(0, 1.3f);
        eqNote.setPadding(0, px(6), 0, 0);
        c5.addView(eqNote);

        LinearLayout c8 = card(root);
        c8.addView(text("결과 공유", 18, INK, true));
        c8.addView(text("진단 수치, 점수, 주파수별 측정값 전체를 글로 복사합니다. 채팅에 붙여넣어 분석을 요청하거나 다른 앱으로 보낼 수 있습니다.", 13, MUT, false));
        shareBtn = button("결과 복사·공유", false);
        shareBtn.setOnClickListener(v -> share());
        c8.addView(shareBtn, lp(px(48), px(10)));
        shareNote = text("", 13, OK, true);
        shareNote.setPadding(0, px(8), 0, 0);
        c8.addView(shareNote);

        LinearLayout c6 = card(root);
        c6.addView(text("측정 환경", 18, INK, true));
        info = text("", 14, MUT, false);
        info.setLineSpacing(0, 1.25f);
        c6.addView(info);

        LinearLayout c7 = card(root);
        c7.addView(text("정확하게 재는 법", 18, INK, true));
        TextView tips = text(
                "• 휴대폰을 운전석 귀 높이에 세로로 들고, 아래쪽 마이크 구멍을 막지 마세요.\n"
                        + "• 차량 EQ·음장 효과를 끄고 평소 듣는 볼륨으로 측정하세요.\n"
                        + "• 휴대폰 블루투스 설정에서 차량의 '전화 통화'는 끄고 '미디어 오디오'만 켜 두세요.\n"
                        + "• 시동을 끄거나 조용한 곳에서 재면 신뢰도가 올라갑니다.\n"
                        + "• 휴대폰 마이크는 50Hz 아래와 12kHz 위가 부정확해 그 대역은 참고용입니다.",
                14, INK, false);
        tips.setLineSpacing(0, 1.3f);
        c7.addView(tips);

        sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFitsSystemWindows(true);
        sv.addView(root);
        setContentView(sv);

        renderDiag(loadDiag(), AudioEngine.chosenSource);
        updateSteps();
        updateInfo(false);
        applyPrefToUi();
        clearResult("");
    }

    // ---------- 단계 표시 ----------

    private View stepHeader(String num, String title) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, 0, 0, px(6));
        TextView n = text(num, 15, 0xFFFFFFFF, true);
        n.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(INK);
        n.setBackground(g);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(px(28), px(28));
        nl.rightMargin = px(10);
        h.addView(n, nl);
        h.addView(text(title, 19, INK, true));
        return h;
    }

    private void updateSteps() {
        boolean done = AudioEngine.chosenSource >= 0;
        if (done) {
            diagStatus.setText("✓ 완료 · '" + AudioEngine.sourceName(AudioEngine.chosenSource) + "' 방식으로 측정합니다");
            diagStatus.setTextColor(OK);
            diagBtn.setText("다시 진단 (약 1분)");
            run.setText("측정 시작");
        } else {
            diagStatus.setText("아직 진단하지 않았습니다. 측정 전에 먼저 진단해 주세요.");
            diagStatus.setTextColor(ACC);
            diagBtn.setText("입력 진단 시작 (약 1분)");
            run.setText("진단 없이 측정");
        }
        styleButton(diagBtn, !done);
        styleButton(run, done);
    }

    private void updateInfo(boolean afterRecording) {
        String s = deviceLine() + "\n가공 없는 마이크 입력 지원: " + (AudioEngine.supportsUnprocessed(this) ? "예" : "아니오") + "\n" + sourceLine();
        if (afterRecording && !AudioEngine.lastInfo.isEmpty()) s += "\n최근 녹음 · " + AudioEngine.lastInfo;
        info.setText(s);
    }

    private String deviceLine() {
        return "기기: " + Build.MANUFACTURER + " " + Build.MODEL + " (안드로이드 " + Build.VERSION.RELEASE + ")";
    }

    private String sourceLine() {
        int c = AudioEngine.chosenSource;
        return "측정 입력 방식: " + (c >= 0 ? AudioEngine.sourceName(c) + " (진단으로 선택)" : AudioEngine.sourceName(AudioEngine.autoSource(this)) + " (자동, 진단 전)");
    }

    // ---------- 입력 진단 ----------

    static final class DiagItem {
        final int src;
        final Dsp.Diag d;
        DiagItem(int src, Dsp.Diag d) { this.src = src; this.d = d; }
    }

    private void onDiag() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        startDiag();
    }

    private void setDiagStatus(String s, int color) {
        diagStatus.setText(s);
        diagStatus.setTextColor(color);
    }

    private void startDiag() {
        setBusy(true);
        diagList.removeAllViews();
        setDiagStatus("진단 준비 중…", INK);
        new Thread(() -> {
            int[] srcs = AudioEngine.candidateSources();
            List<DiagItem> items = new ArrayList<>();
            int best = -1;
            double bestScore = -1e9;
            try {
                for (int i = 0; i < srcs.length; i++) {
                    String name = AudioEngine.sourceName(srcs[i]);
                    String label = (i + 1) + "/" + srcs.length + " '" + name + "' 방식 녹음 중… ";
                    ui.post(() -> setDiagStatus(label, INK));
                    float[] rec = AudioEngine.recordWith(this, srcs[i], Dsp.diagStimulus(AudioEngine.FS), label, msg -> ui.post(() -> setDiagStatus(msg, INK)));
                    if (rec == null) {
                        items.add(new DiagItem(srcs[i], null));
                        continue;
                    }
                    ui.post(() -> setDiagStatus(label.replace("녹음 중… ", "분석 중…"), INK));
                    Dsp.Diag d = Dsp.diagnose(rec, AudioEngine.FS);
                    boolean agc = d.linErr > 1.5;
                    double sc = (d.snr < 20 ? -1000 : 0) + (agc ? -100 : 0) - d.linErr * 5 + d.lowRel + 0.5 * d.highRel + Math.min(d.snr, 50) * 0.1;
                    items.add(new DiagItem(srcs[i], d));
                    if (sc > bestScore) { bestScore = sc; best = srcs[i]; }
                    Thread.sleep(500);
                }
                saveDiag(items);
                final int chosen = best;
                ui.post(() -> {
                    if (chosen >= 0) {
                        AudioEngine.chosenSource = chosen;
                        store.edit().putInt("source", chosen).apply();
                        session.clear();
                        clearResult("입력 진단이 끝났습니다. 이제 측정을 시작하세요.");
                        renderDiag(items, chosen);
                    } else {
                        renderDiag(items, -1);
                    }
                    setBusy(false);
                    updateSteps();
                    if (chosen < 0) setDiagStatus("진단 실패: 쓸 수 있는 입력 방식이 없습니다.", ACC);
                    updateInfo(true);
                });
            } catch (Throwable e) {
                String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다." : String.valueOf(e.getMessage());
                ui.post(() -> {
                    renderDiag(items, -1);
                    setBusy(false);
                    updateSteps();
                    setDiagStatus("진단 실패: " + msg, ACC);
                });
            }
        }).start();
    }

    private void saveDiag(List<DiagItem> items) {
        StringBuilder s = new StringBuilder();
        for (DiagItem it : items) {
            if (s.length() > 0) s.append(';');
            s.append(it.src);
            if (it.d != null) s.append(String.format(Locale.US, ",%.3f,%.3f,%.3f,%.3f", it.d.linErr, it.d.lowRel, it.d.highRel, it.d.snr));
        }
        store.edit().putString("diag", s.toString()).apply();
    }

    private List<DiagItem> loadDiag() {
        List<DiagItem> items = new ArrayList<>();
        String s = store.getString("diag", "");
        if (s.isEmpty()) return items;
        try {
            for (String part : s.split(";")) {
                String[] f = part.split(",");
                Dsp.Diag d = null;
                if (f.length == 5) {
                    d = new Dsp.Diag();
                    d.linErr = Double.parseDouble(f[1]);
                    d.lowRel = Double.parseDouble(f[2]);
                    d.highRel = Double.parseDouble(f[3]);
                    d.snr = Double.parseDouble(f[4]);
                }
                items.add(new DiagItem(Integer.parseInt(f[0]), d));
            }
        } catch (RuntimeException e) {
            items.clear();
        }
        return items;
    }

    private void renderDiag(List<DiagItem> items, int chosen) {
        diagList.removeAllViews();
        if (items.isEmpty()) return;
        double bestLow = -1e9, bestHigh = -1e9;
        for (DiagItem it : items) if (it.d != null) { bestLow = Math.max(bestLow, it.d.lowRel); bestHigh = Math.max(bestHigh, it.d.highRel); }
        for (DiagItem it : items) {
            boolean rec = it.src == chosen;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(px(12), px(10), px(12), px(10));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(12));
            g.setColor(rec ? 0xFFFFF1EF : it.d == null ? BG : CARD);
            g.setStroke(px(rec ? 2 : 1), rec ? ACC : LINE);
            box.setBackground(g);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(AudioEngine.sourceName(it.src), 16, it.d == null ? MUT : INK, true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (rec) head.addView(pill("추천 · 사용 중", ACC));
            box.addView(head);
            if (it.d == null) {
                box.addView(text("이 기기에서 쓸 수 없는 방식", 13, MUT, false));
            } else {
                boolean agc = it.d.linErr > 1.5;
                box.addView(metric("자동 음량 조절", agc ? "있음" : "없음", String.format(Locale.US, "오차 %.1fdB", it.d.linErr), agc ? ACC : OK));
                box.addView(metric("저음 보존", keep(it.d.lowRel - bestLow), null, lvl(it.d.lowRel - bestLow)));
                box.addView(metric("고음 보존", keep(it.d.highRel - bestHigh), null, lvl(it.d.highRel - bestHigh)));
                int sc = it.d.snr >= 40 ? OK : it.d.snr >= 25 ? WARN : ACC;
                box.addView(metric("신뢰도", it.d.snr >= 40 ? "높음" : it.d.snr >= 25 ? "보통" : "낮음", Math.round(it.d.snr) + "dB", sc));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(8);
            diagList.addView(box, lp);
        }
        TextView note = text("저음·고음 보존은 가장 좋은 방식과 비교한 차이입니다. 자동 음량 조절이 없고 저음을 가장 잘 지키는 방식을 추천합니다.", 12, MUT, false);
        note.setPadding(0, px(8), 0, 0);
        diagList.addView(note);
    }

    private static String keep(double rel) {
        return rel >= -1 ? "가장 좋음" : String.format(Locale.US, "%.1fdB 손실", -rel);
    }

    private static int lvl(double rel) {
        return rel >= -1.5 ? OK : rel >= -4 ? WARN : ACC;
    }

    private View metric(String label, String val, String sub, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(7), 0, 0);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(8), px(8));
        dl.rightMargin = px(10);
        row.addView(dot, dl);
        row.addView(text(label, 14, MUT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (sub != null) row.addView(text(sub + "   ", 12, MUT, false));
        row.addView(text(val, 14, color, true));
        return row;
    }

    private TextView pill(String s, int color) {
        TextView t = text(s, 12, 0xFFFFFFFF, true);
        t.setPadding(px(10), px(3), px(10), px(3));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(20));
        g.setColor(color);
        t.setBackground(g);
        return t;
    }

    // ---------- 측정 ----------

    private void onRun() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        startMeasure();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && code == 2) startDiag();
        else if (ok && code == 1) startMeasure();
        else setStatus("마이크 권한이 없어 진행할 수 없습니다. 설정에서 마이크 권한을 허용해 주세요.", true);
    }

    private void setBusy(boolean b) {
        busy = b;
        run.setEnabled(!b);
        reset.setEnabled(!b);
        diagBtn.setEnabled(!b);
        run.setAlpha(b ? 0.6f : 1f);
        diagBtn.setAlpha(b ? 0.6f : 1f);
        if (b) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startMeasure() {
        final boolean lr = modeLr.isChecked();
        setBusy(true);
        setStatus("준비 중…", false);
        new Thread(() -> {
            try {
                float[] rec = AudioEngine.record(this, lr, msg -> ui.post(() -> setStatus(msg, false)));
                ui.post(() -> setStatus("분석 중…", false));
                if (rec.length < AudioEngine.FS * (lr ? 19 : 9)) throw new IllegalStateException("녹음이 너무 짧습니다. 다시 측정해 주세요.");
                Dsp.Meas m = Dsp.measure(rec, AudioEngine.FS, lr);
                ui.post(() -> {
                    session.add(m);
                    render(Dsp.summarize(session, pref));
                    setStatus("측정 완료 (" + session.size() + "곳) · 위치를 조금 옮겨 한 번 더 재면 평균이 더 정확해집니다", false);
                    setBusy(false);
                    updateInfo(true);
                    sv.post(() -> sv.smoothScrollTo(0, resultCard.getTop() - px(12)));
                });
            } catch (Throwable e) {
                String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다. 다른 앱을 닫고 다시 시도해 주세요." : String.valueOf(e.getMessage());
                ui.post(() -> { setStatus("측정 실패: " + msg, true); setBusy(false); });
            }
        }).start();
    }

    private void clearResult(String msg) {
        lastResult = null;
        chart.setResult(null);
        setLegend(null);
        score.setText("--");
        grade.setText("측정 전");
        count.setText("2단계 측정을 하면 표시됩니다");
        details.setText("측정 후 표시됩니다.");
        eqView.setGains(null);
        eqNote.setText("");
        setStatus(msg, false);
    }

    private void render(Dsp.Result r) {
        lastResult = r;
        chart.setResult(r);
        setLegend(r);
        int t = (int) Math.round(r.total);
        score.setText(String.valueOf(t));
        grade.setText(gradeName(t));
        count.setText("측정 위치 " + r.count + "곳 평균 · " + pref.name + " 기준");

        StringBuilder s = new StringBuilder();
        s.append(bar("음색 균형", r.tone)).append(bar("저음 확장", r.bass)).append(bar("깨끗함", r.clar));
        if (r.lr) s.append(bar("좌우 밸런스", r.balScore));
        s.append("\n");
        String rel = r.snr >= 40 ? "높음" : r.snr >= 25 ? "보통" : "낮음";
        s.append("목표 대비 편차  ±").append(f1(r.rms)).append(" dB\n");
        s.append("저음 한계  ").append(r.fb <= 20 ? "20Hz 이하" : fmtF(r.fb)).append("\n");
        s.append("왜곡(추정)  ").append(f1(r.thd)).append(" %\n");
        s.append("측정 신뢰도  ").append(rel).append(" (").append(Math.round(r.snr)).append(" dB)\n");
        if (r.lr) {
            s.append("좌우 음량 차  ").append(r.bal > 0 ? "왼쪽" : "오른쪽").append(" +").append(f1(Math.abs(r.bal))).append(" dB\n");
            s.append("도달 시간 차  ").append(r.dly > 0 ? "왼쪽" : "오른쪽").append("이 ").append(f1(Math.abs(r.dly)))
                    .append(" ms 빠름 (약 ").append(Math.round(Math.abs(r.dly) * 34.3)).append(" cm)\n");
        }
        List<String> notes = new ArrayList<>();
        if (AudioEngine.lastOutputIsPhoneSpeaker) notes.add("⚠ 소리가 휴대폰 스피커로 나왔습니다. 차량에 연결한 뒤 다시 측정하세요.");
        if (AudioEngine.lastInputNotBuiltin) notes.add("⚠ 내장 마이크가 아닌 다른 입력이 쓰였습니다. 측정 환경을 확인하세요.");
        if (r.clip > 0.98) notes.add("⚠ 마이크 입력이 넘쳤습니다. 볼륨을 조금 낮추고 다시 재세요.");
        if (r.snr < 25) notes.add("⚠ 신호가 소음에 묻혔습니다. 볼륨을 올리거나 시동을 끄고 다시 재세요.");
        int k = 0;
        for (Dsp.Issue x : r.issues) {
            if (k++ >= 6) break;
            String why = x.v > 0 ? (x.f < 300 ? "저음이 웅웅거릴 수 있습니다" : "소리가 쏘거나 거칠게 들릴 수 있습니다")
                    : (x.f < 300 ? "저음이 빈약하게 들립니다" : "이 대역이 묻혀 답답하게 들립니다");
            notes.add("• " + fmtF(x.f) + " 부근 " + (x.v > 0 ? "+" : "") + Math.round(x.v) + " dB — " + why);
        }
        if (r.lr && Math.abs(r.dly) > 1) notes.add("• 가까운 쪽 스피커 소리가 먼저 와서 소리 중심이 한쪽으로 쏠립니다. 헤드유닛에 청취 위치(타임 얼라인먼트) 설정이 있다면 운전석으로 맞춰 보세요.");
        if (r.thd > 3) notes.add("• 이 볼륨에서 찌그러짐이 큽니다. 한두 단계 낮춰 다시 재 보세요.");
        if (notes.isEmpty()) notes.add("목표 곡선에서 크게 벗어난 대역이 없습니다.");
        s.append("\n눈에 띄는 점\n");
        for (String n : notes) s.append(n).append("\n");
        details.setText(s.toString().trim());

        eqView.setGains(r.eqGain);
        setEqNote(r.eqGain);
    }

    static String gradeName(int t) {
        return t >= 85 ? "매우 좋음" : t >= 70 ? "좋음" : t >= 55 ? "보통" : "개선 필요";
    }

    private void setEqNote(int[] g) {
        int mi = 0;
        for (int i = 0; i < g.length; i++) if (Math.abs(g[i]) > Math.abs(g[mi])) mi = i;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "■ ", EqView.UP);
        sb.append("올리기     ");
        append(sb, "■ ", EqView.DOWN);
        sb.append("내리기\n");
        if (g[mi] == 0) sb.append("목표 곡선에 가깝습니다. 지금 EQ 설정을 그대로 두세요.");
        else sb.append("가장 먼저 손볼 대역: ").append(fmtF(Dsp.EQ_BANDS[mi])).append(" ").append(signed(g[mi])).append("dB");
        sb.append("\n16kHz는 휴대폰 마이크 한계로 참고만 하세요.");
        eqNote.setText(sb);
    }

    private String bar(String name, double v) {
        int n = (int) Math.round(v / 10);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(i < n ? "■" : "□");
        return name + "  " + b + "  " + Math.round(v) + "\n";
    }

    private void setLegend(Dsp.Result r) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "┅ ", 0xFF8A8F96);
        sb.append("목표 곡선 · ").append(pref == null ? "기본" : pref.name);
        if (r != null) for (int i = 0; i < r.ch.length; i++) {
            sb.append("    ");
            append(sb, "━ ", FrView.COLORS[i % FrView.COLORS.length]);
            sb.append(r.ch[i].name);
        }
        legend.setText(sb);
    }

    // ---------- 결과 공유 ----------

    private void share() {
        String t = buildReport();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("차량 사운드 측정 결과", t));
            shareNote.setText("✓ 결과를 복사했습니다. 채팅 입력창에 붙여넣거나, 공유할 앱을 고르세요.");
        } catch (RuntimeException e) {
            shareNote.setText("복사하지 못했습니다. 공유할 앱을 골라 주세요.");
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "차량 사운드 측정 결과");
        i.putExtra(Intent.EXTRA_TEXT, t);
        try {
            startActivity(Intent.createChooser(i, "측정 결과 공유"));
        } catch (RuntimeException ignored) { }
    }

    private String buildReport() {
        StringBuilder s = new StringBuilder();
        s.append("[차량 사운드 측정기 결과]\n");
        s.append(deviceLine()).append("\n");
        s.append("가공 없는 마이크 입력 지원: ").append(AudioEngine.supportsUnprocessed(this) ? "예" : "아니오").append("\n");
        s.append(sourceLine()).append("\n");
        if (!AudioEngine.lastInfo.isEmpty()) s.append("최근 녹음 · ").append(AudioEngine.lastInfo.replace("\n", " / ")).append("\n");
        s.append("취향: ").append(pref.name).append(" (저음 ").append(signed((int) Math.round(pref.bass)))
                .append(", 중저음 ").append(signed((int) Math.round(pref.lowMid)))
                .append(", 보컬 ").append(signed((int) Math.round(pref.vocal)))
                .append(", 고음 ").append(signed((int) Math.round(pref.treble))).append(")\n");

        List<DiagItem> di = loadDiag();
        if (!di.isEmpty()) {
            s.append("\n[입력 진단] 방식: 자동음량 오차dB, 저음(중역 대비)dB, 고음(중역 대비)dB, 신뢰도dB\n");
            for (DiagItem it : di) {
                s.append(AudioEngine.sourceName(it.src)).append(it.src == AudioEngine.chosenSource ? " [사용 중]" : "").append(": ");
                if (it.d == null) s.append("사용 불가\n");
                else s.append(String.format(Locale.US, "%.1f, %+.1f, %+.1f, %.0f\n", it.d.linErr, it.d.lowRel, it.d.highRel, it.d.snr));
            }
        }

        Dsp.Result r = lastResult;
        if (r == null) {
            s.append("\n측정 결과 없음\n");
            return s.toString();
        }
        int t = (int) Math.round(r.total);
        s.append("\n[측정] ").append(r.lr ? "좌·우 분리" : "스테레오").append(", 위치 ").append(r.count).append("곳 평균\n");
        s.append(String.format(Locale.US, "점수 %d (%s) · 음색 %d · 저음 %d · 깨끗함 %d", t, gradeName(t),
                Math.round(r.tone), Math.round(r.bass), Math.round(r.clar)));
        if (r.lr) s.append(String.format(Locale.US, " · 밸런스 %d", Math.round(r.balScore)));
        s.append("\n");
        s.append(String.format(Locale.US, "편차 ±%.1fdB · 저음 한계 %s · 왜곡 %.1f%% · 신뢰도 %.0fdB · 입력 최대 %.2f\n",
                r.rms, r.fb <= 20 ? "20Hz 이하" : fmtF(r.fb), r.thd, r.snr, r.clip));
        if (r.lr) s.append(String.format(Locale.US, "좌우 음량 차 %+.1fdB(+는 왼쪽이 큼) · 도달 시간 차 %+.1fms(+는 왼쪽이 먼저)\n", r.bal, r.dly));
        s.append("EQ 제안: ");
        for (int i = 0; i < Dsp.EQ_BANDS.length; i++) {
            if (i > 0) s.append(", ");
            s.append(fmtF(Dsp.EQ_BANDS[i])).append(" ").append(signed(r.eqGain[i]));
        }
        s.append("\n");

        boolean spread = session.size() > 1;
        s.append("\n[주파수 응답] 1/6옥타브 간격, dB (300Hz~3kHz 평균을 목표 곡선에 맞춤)\nHz");
        for (Dsp.Channel c : r.ch) s.append(", ").append(c.name);
        s.append(", 목표");
        if (spread) s.append(", 위치간 편차폭");
        s.append("\n");
        for (int i = 0; i < Dsp.NF; i += 4) {
            double f = Dsp.FR[i];
            s.append(f < 1000 ? String.valueOf(Math.round(f)) : String.format(Locale.US, "%.0f", f));
            for (Dsp.Channel c : r.ch) s.append(String.format(Locale.US, ", %.1f", c.n[i]));
            s.append(String.format(Locale.US, ", %.1f", Dsp.target(f, pref)));
            if (spread) {
                double lo = 1e9, hi = -1e9;
                for (Dsp.Meas m : session) { lo = Math.min(lo, m.ch[0].n[i]); hi = Math.max(hi, m.ch[0].n[i]); }
                s.append(String.format(Locale.US, ", %.1f", hi - lo));
            }
            s.append("\n");
        }
        return s.toString();
    }

    // ---------- 취향 ----------

    static final String[] SLIDER_NAMES = {"저음", "중저음", "보컬", "고음"};
    static final String[] SLIDER_HINTS = {"쿵쿵 울리는 깊은 저음", "따뜻함·펀치감", "목소리의 또렷함", "밝기·반짝임"};

    private void buildTasteCard(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(text("내 취향", 18, INK, true));
        c.addView(text("취향을 바꾸면 다시 측정하지 않아도 점수가 바로 다시 계산됩니다.", 13, MUT, false));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(10), 0, px(6));
        for (int i = 0; i < Dsp.PRESETS.length; i++) {
            final int idx = i;
            Button b = button(Dsp.PRESETS[i].name, false);
            b.setTextSize(15);
            b.setPadding(px(14), 0, px(14), 0);
            b.setOnClickListener(v -> choosePreset(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44));
            lp.rightMargin = px(8);
            row.addView(b, lp);
            chips.add(b);
        }
        hs.addView(row);
        c.addView(hs);

        for (int k = 0; k < 4; k++) {
            final int idx = k;
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, px(10), 0, 0);
            TextView name = text(SLIDER_NAMES[k], 16, INK, true);
            TextView hint = text("  " + SLIDER_HINTS[k], 13, MUT, false);
            TextView val = text("0", 16, INK, true);
            val.setGravity(Gravity.END);
            head.addView(name);
            head.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(val);
            c.addView(head);
            SeekBar sb = new SeekBar(this);
            sb.setMax(12);
            sb.setPadding(px(8), px(10), px(8), px(10));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int prog, boolean user) {
                    sliderVals[idx].setText(signed(prog - 6));
                    if (!syncing && user) onSliderChanged();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { savePref(); }
            });
            c.addView(sb);
            LinearLayout ends = new LinearLayout(this);
            ends.setOrientation(LinearLayout.HORIZONTAL);
            TextView lo = text("적게", 12, MUT, false), hi = text("많이", 12, MUT, false);
            hi.setGravity(Gravity.END);
            ends.addView(lo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ends.addView(hi, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            c.addView(ends);
            sliders[k] = sb;
            sliderVals[k] = val;
        }
        prefHint = text("", 13, MUT, false);
        prefHint.setPadding(0, px(10), 0, 0);
        c.addView(prefHint);
    }

    private void choosePreset(int idx) {
        presetIdx = idx;
        Dsp.Pref p = Dsp.PRESETS[idx];
        pref = p.withValues(p.name, p.bass, p.lowMid, p.vocal, p.treble);
        applyPrefToUi();
        savePref();
        rescore();
    }

    private void onSliderChanged() {
        Dsp.Pref base = Dsp.PRESETS[presetIdx];
        pref = base.withValues("내 설정", sliders[0].getProgress() - 6, sliders[1].getProgress() - 6, sliders[2].getProgress() - 6, sliders[3].getProgress() - 6);
        styleChips();
        updateHint();
        rescore();
    }

    private void applyPrefToUi() {
        syncing = true;
        double[] v = {pref.bass, pref.lowMid, pref.vocal, pref.treble};
        for (int k = 0; k < 4; k++) {
            sliders[k].setProgress((int) Math.round(v[k]) + 6);
            sliderVals[k].setText(signed((int) Math.round(v[k])));
        }
        syncing = false;
        styleChips();
        updateHint();
        chart.setPref(pref);
        setLegend(lastResult);
    }

    private void styleChips() {
        for (int i = 0; i < chips.size(); i++) {
            boolean on = i == presetIdx && pref.name.equals(Dsp.PRESETS[i].name);
            Button b = chips.get(i);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(22));
            g.setColor(on ? INK : CARD);
            g.setStroke(px(1), on ? INK : LINE);
            b.setBackground(g);
            b.setTextColor(on ? 0xFFFFFFFF : INK);
        }
    }

    private void updateHint() {
        String n = pref.name;
        String h;
        if (n.equals("락") || n.equals("팝") || n.equals("중저음 선호")) h = "평소 크게 듣는 편이라면 그 볼륨으로 측정하세요. 큰 볼륨에서의 찌그러짐도 점수에 반영됩니다.";
        else if (n.equals("또렷한 보컬") || n.equals("발라드")) h = "1~4kHz(목소리 대역)의 편차를 더 엄격하게 봅니다.";
        else if (n.equals("내 설정")) h = "'" + Dsp.PRESETS[presetIdx].name + "' 프리셋에서 조정한 값입니다. 그래프의 점선이 내 목표 곡선입니다.";
        else h = "하만 차량 연구를 단순화한 기본 목표 곡선입니다.";
        chart.setPref(pref);
        prefHint.setText(h);
    }

    private void rescore() {
        chart.setPref(pref);
        if (session.isEmpty() || busy) { setLegend(lastResult); return; }
        render(Dsp.summarize(session, pref));
    }

    private void loadPref() {
        presetIdx = Math.max(0, Math.min(Dsp.PRESETS.length - 1, store.getInt("preset", 0)));
        Dsp.Pref base = Dsp.PRESETS[presetIdx];
        String name = store.getString("name", base.name);
        pref = base.withValues(name, store.getFloat("b", (float) base.bass), store.getFloat("lm", (float) base.lowMid),
                store.getFloat("v", (float) base.vocal), store.getFloat("t", (float) base.treble));
    }

    private void savePref() {
        store.edit().putInt("preset", presetIdx).putString("name", pref.name)
                .putFloat("b", (float) pref.bass).putFloat("lm", (float) pref.lowMid)
                .putFloat("v", (float) pref.vocal).putFloat("t", (float) pref.treble).apply();
    }

    // ---------- 공통 ----------

    private static void append(SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void setStatus(String s, boolean warn) {
        status.setText(s);
        status.setTextColor(warn ? ACC : INK);
    }

    static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    static String f1(double v) { return String.format(Locale.US, "%.1f", v); }

    static String fmtF(double f) {
        if (f >= 1000) {
            String s = f >= 10000 ? String.valueOf(Math.round(f / 1000)) : String.format(Locale.US, "%.1f", f / 1000);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s + "kHz";
        }
        return Math.round(f) + "Hz";
    }

    private int px(float v) { return Math.round(v * dp); }

    private LinearLayout.LayoutParams lp(int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        p.topMargin = top;
        return p;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(primary ? 18 : 15);
        styleButton(b, primary);
        return b;
    }

    private void styleButton(Button b, boolean primary) {
        b.setTextColor(primary ? 0xFFFFFFFF : INK);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(primary ? ACC : CARD);
        if (!primary) g.setStroke(px(1), LINE);
        b.setBackground(g);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = px(14);
        c.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(14));
        g.setColor(CARD);
        g.setStroke(px(1), LINE);
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(12);
        parent.addView(c, lp);
        return c;
    }
}
