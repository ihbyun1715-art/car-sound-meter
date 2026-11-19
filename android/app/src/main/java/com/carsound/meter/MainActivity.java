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
    private TextView score, grade, count, status, legend, eqNote, info, diagStatus, shareNote, prefHint;
    private LinearLayout detailBox, eq3Box, eq9Box, knobRow;
    private TextView knobNote, learnText, learnStatus;
    private Button eqTab3, eqTab9, learnBtn, learnClear;
    private int learnStep = 0;
    private double[] learnBase;
    private double[][] learnTmp = new double[3][];
    private final double[][] knobShape = new double[3][];
    private Dsp.KnobFit lastKnob;
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
        loadKnobs();
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
        s1.addView(text("차에서 평소 듣는 볼륨으로, 휴대폰을 운전석 귀 높이에 두고 움직이지 마세요. 녹음마다 처음 3초 동안 작은 '쉬' 소리로 스피커 연결을 깨웁니다.", 14, MUT, false));
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
        col.addView(text("100점 만점 · 목표 곡선에 가깝고 찌그러짐이 적을수록 높습니다", 12, MUT, false));
        row.addView(col);
        resultCard.addView(row);
        chart = new FrView(this);
        resultCard.addView(chart);
        legend = text("", 13, MUT, false);
        resultCard.addView(legend);

        buildTasteCard(root);

        LinearLayout c4 = card(root);
        c4.addView(text("세부 결과", 18, INK, true));
        detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        c4.addView(detailBox);

        buildEqCard(root);

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
        updateLearnUi();
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
            diagBtn.setText("다시 진단 (약 1분 30초)");
            run.setText("측정 시작");
        } else {
            diagStatus.setText("아직 진단하지 않았습니다. 측정 전에 먼저 진단해 주세요.");
            diagStatus.setTextColor(ACC);
            diagBtn.setText("입력 진단 시작 (약 1분 30초)");
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
                    if (d.snr >= 20 && sc > bestScore) { bestScore = sc; best = srcs[i]; }
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
                    if (chosen < 0) setDiagStatus("진단 실패: 테스트 신호가 제대로 녹음되지 않았습니다. 블루투스 '미디어 오디오' 연결과 볼륨을 확인하고 다시 진단해 주세요.", ACC);
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
        for (DiagItem it : items) if (it.d != null && it.d.snr >= 20) { bestLow = Math.max(bestLow, it.d.lowRel); bestHigh = Math.max(bestHigh, it.d.highRel); }
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
            } else if (it.d.snr < 20) {
                box.addView(metric("결과", "무효", "신호가 제대로 녹음되지 않음", ACC));
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

    // ---------- 세부 결과 ----------

    static final String[] LV = {"아주 좋음", "좋음", "보통", "아쉬움"};

    static int lvColor(int l) { return l <= 1 ? OK : l == 2 ? WARN : ACC; }

    private void renderDetails(Dsp.Result r) {
        detailBox.removeAllViews();
        List<String> warns = new ArrayList<>();
        if (AudioEngine.lastOutputIsPhoneSpeaker) warns.add("소리가 휴대폰 스피커로 나왔습니다. 차량에 연결한 뒤 다시 측정하세요.");
        if (AudioEngine.lastInputNotBuiltin) warns.add("내장 마이크가 아닌 다른 입력이 쓰였습니다. 측정 환경을 확인하세요.");
        if (r.clip > 0.98) warns.add("마이크 입력이 넘쳤습니다. 볼륨을 조금 낮추고 다시 재세요.");
        if (r.snr < 20) warns.add("신호가 소음에 묻혀 결과를 믿기 어렵습니다. 볼륨을 올리거나 시동을 끄고 다시 재세요.");
        for (String w : warns) detailBox.addView(banner("⚠ " + w));

        int tl = r.rms <= 3 ? 0 : r.rms <= 4.5 ? 1 : r.rms <= 6 ? 2 : 3;
        addMetric("음색 균형", LV[tl], lvColor(tl), r.tone, "목표 곡선과 평균 ±" + f1(r.rms) + "dB 차이",
                "저음·중음·고음의 비율이 목표 곡선과 얼마나 비슷한지 봅니다. ±3dB 이내면 아주 좋고, ±6dB를 넘으면 특정 소리가 튀거나 묻혀 들립니다.");
        int bl = r.fb <= 40 ? 0 : r.fb <= 60 ? 1 : r.fb <= 90 ? 2 : 3;
        addMetric("저음 깊이", LV[bl], lvColor(bl), r.bass,
                r.fb <= 20 ? "가장 낮은 저음(20Hz)까지 힘 있게 나옵니다" : "약 " + fmtF(r.fb) + " 아래부터 저음이 약해집니다",
                "얼마나 낮은 저음까지 충분히 나오는지 봅니다. 40Hz 아래까지 나오면 아주 깊고, 80Hz 위에서 약해지면 저음이 얕게 느껴집니다.");
        int dl = r.thd < 1 ? 0 : r.thd < 2 ? 1 : r.thd < 3 ? 2 : 3;
        addMetric("깨끗함", LV[dl], lvColor(dl), r.clar, "찌그러짐(왜곡) " + f1(r.thd) + "%",
                "지금 볼륨에서 스피커나 앰프가 소리를 찌그러뜨리는 정도입니다. 1% 미만이면 귀로 느끼기 어렵고, 3%를 넘으면 거칠게 들립니다.");
        if (r.lr) {
            double ab = Math.abs(r.bal), ad = Math.abs(r.dly);
            int ll = (ab <= 1 && ad <= 1) ? 0 : (ab <= 2 && ad <= 2) ? 1 : ab <= 4 ? 2 : 3;
            addMetric("좌우 밸런스", LV[ll], lvColor(ll), r.balScore,
                    (r.bal > 0 ? "왼쪽" : "오른쪽") + "이 " + f1(ab) + "dB 크고, " + (r.dly > 0 ? "왼쪽" : "오른쪽") + " 소리가 " + f1(ad)
                            + "ms 먼저 도착 (거리 차 약 " + Math.round(ad * 34.3) + "cm)",
                    "운전석은 왼쪽 스피커에 가까워 소리가 왼쪽으로 쏠리기 쉽습니다. 음량 차 1dB, 시간 차 1ms 이하가 이상적입니다."
                            + (ad > 1 ? " 헤드유닛에 청취 위치(운전석 중심) 설정이 있다면 켜 보세요." : ""));
        }
        int sl = r.snr >= 40 ? 0 : r.snr >= 30 ? 1 : r.snr >= 20 ? 2 : 3;
        String[] SN = {"높음", "충분", "보통", "낮음"};
        addMetric("측정 신뢰도", SN[sl], lvColor(sl), -1, "테스트 신호가 주변 소음보다 " + Math.round(r.snr) + "dB 큼",
                "점수가 아니라 이번 측정을 얼마나 믿을 수 있는지입니다. 30dB 이상이면 충분하고, 20dB 미만이면 다시 측정하세요.");

        TextView h = text("눈에 띄는 구간", 16, INK, true);
        h.setPadding(0, px(16), 0, px(2));
        detailBox.addView(h);
        if (r.issues.isEmpty()) detailBox.addView(text("목표 곡선에서 크게 벗어난 구간이 없습니다.", 14, MUT, false));
        int k = 0;
        for (Dsp.Issue x : r.issues) {
            if (k++ >= 6) break;
            addIssue(x);
        }
        TextView g = text("그래프에서 빨간 선이 점선(목표)보다 위에 있으면 그 소리가 크게, 아래에 있으면 작게 들린다는 뜻입니다.", 12, MUT, false);
        g.setPadding(0, px(10), 0, 0);
        detailBox.addView(g);
    }

    private void addMetric(String title, String verdict, int color, double score, String value, String meaning) {
        if (detailBox.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(LINE);
            detailBox.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(12), 0, px(12));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(title, 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(pill(verdict, color));
        box.addView(head);
        TextView v = text(value, 15, INK, false);
        v.setPadding(0, px(6), 0, 0);
        box.addView(v);
        if (score >= 0) box.addView(meter(score));
        TextView m = text(meaning, 13, MUT, false);
        m.setLineSpacing(0, 1.25f);
        m.setPadding(0, px(6), 0, 0);
        box.addView(m);
        detailBox.addView(box);
    }

    private View meter(double score) {
        int sc = (int) Math.round(score);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(8), 0, 0);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(px(4));
        tg.setColor(LINE);
        track.setBackground(tg);
        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setCornerRadius(px(4));
        fg.setColor(sc >= 70 ? OK : sc >= 50 ? WARN : ACC);
        fill.setBackground(fg);
        track.addView(fill, new LinearLayout.LayoutParams(0, px(8), (float) Math.max(sc, 0.5)));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, px(8), (float) Math.max(100 - sc, 0.5)));
        row.addView(track, new LinearLayout.LayoutParams(0, px(8), 1));
        TextView t = text("100점 중 " + sc + "점", 12, MUT, false);
        t.setPadding(px(10), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private TextView banner(String s) {
        TextView t = text(s, 14, ACC, true);
        t.setPadding(px(12), px(10), px(12), px(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(0xFFFFF1EF);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void addIssue(Dsp.Issue x) {
        boolean up = x.v > 0;
        String range = Math.round(x.fLo) == Math.round(x.fHi) ? fmtF(x.f) : fmtF(x.fLo) + "~" + fmtF(x.fHi);
        String title = range + " · 목표보다 " + (up ? "+" : "") + Math.round(x.v) + "dB " + (up ? "큼" : "작음");
        String knob = x.f < 300 ? "저음" : x.f < 2000 ? "중음" : "고음";
        String why;
        if (x.narrow && !up) why = "좁게 꺼진 골입니다. 스피커끼리 소리가 상쇄되거나 실내 공진 때문에 생기며, EQ로 올려도 잘 메워지지 않습니다. 귀로는 크게 느껴지지 않는 경우가 많아요.";
        else if (x.narrow) why = "좁게 튀는 공진입니다. 특정 음에서 소리가 붕 뜨거나 울릴 수 있어요. " + knob + "을 조금 내려 보세요.";
        else if (up) why = x.f < 300 ? "저음이 웅웅거리거나 둔탁할 수 있어요. 저음을 조금 내려 보세요."
                : x.f < 2000 ? "소리가 통 안에서 울리는 듯 답답할 수 있어요. 중음을 조금 내려 보세요."
                : "소리가 쏘거나 귀가 피곤할 수 있어요. 고음을 조금 내려 보세요.";
        else why = x.f < 300 ? "저음이 빈약하게 느껴질 수 있어요." : x.f < 4000 ? "목소리·악기가 멀고 흐릿하게 들릴 수 있어요." : "소리가 둔하고 답답하게 들릴 수 있어요.";
        if (x.posDep) why += " 앉은 위치에 따라 크게 달라지는 구간입니다.";
        if (x.f >= 10000) why += " 단, 10kHz 이상은 휴대폰 마이크 특성일 수 있어 참고만 하세요.";
        if (x.f < 46) why += " 40Hz 근처 값은 측정 시작 부분이 잘려 생긴 것일 수도 있습니다.";

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(10), 0, 0);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(up ? ACC : EqView.UP);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(10), px(10));
        dl.rightMargin = px(10);
        dl.topMargin = px(5);
        row.addView(dot, dl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 14, INK, true));
        TextView w = text(why, 13, MUT, false);
        w.setLineSpacing(0, 1.25f);
        col.addView(w);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        detailBox.addView(row);
    }

    // ---------- EQ 제안 (3밴드 / 9밴드) ----------

    private void buildEqCard(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(text("EQ 조정 제안", 18, INK, true));
        c.addView(text("내 차 이퀄라이저 형태를 고르세요.", 13, MUT, false));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, px(8), 0, 0);
        eqTab3 = button("3밴드 (저·중·고)", false);
        eqTab9 = button("9밴드 그래픽", false);
        eqTab3.setTextSize(14);
        eqTab9.setTextSize(14);
        eqTab3.setOnClickListener(v -> setEqMode(true));
        eqTab9.setOnClickListener(v -> setEqMode(false));
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, px(44), 1);
        t1.rightMargin = px(6);
        tabs.addView(eqTab3, t1);
        tabs.addView(eqTab9, new LinearLayout.LayoutParams(0, px(44), 1));
        c.addView(tabs);

        eq3Box = new LinearLayout(this);
        eq3Box.setOrientation(LinearLayout.VERTICAL);
        knobRow = new LinearLayout(this);
        knobRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.topMargin = px(12);
        eq3Box.addView(knobRow, kl);
        knobNote = text("", 13, MUT, false);
        knobNote.setLineSpacing(0, 1.25f);
        knobNote.setPadding(0, px(8), 0, 0);
        eq3Box.addView(knobNote);
        TextView lt = text("내 차 노브 학습 (더 정확하게)", 16, INK, true);
        lt.setPadding(0, px(18), 0, px(2));
        eq3Box.addView(lt);
        learnText = text("", 13, MUT, false);
        learnText.setLineSpacing(0, 1.25f);
        eq3Box.addView(learnText);
        learnBtn = button("", false);
        learnBtn.setOnClickListener(v -> onLearn());
        eq3Box.addView(learnBtn, lp(px(48), px(10)));
        learnClear = button("", false);
        learnClear.setTextSize(13);
        learnClear.setOnClickListener(v -> onLearnClear());
        eq3Box.addView(learnClear, lp(px(40), px(6)));
        learnStatus = text("", 13, OK, true);
        learnStatus.setPadding(0, px(8), 0, 0);
        eq3Box.addView(learnStatus);
        c.addView(eq3Box);

        eq9Box = new LinearLayout(this);
        eq9Box.setOrientation(LinearLayout.VERTICAL);
        eq9Box.addView(text("각 대역을 막대만큼 올리거나 내려 보세요. 대역 이름이 조금 달라도 가장 가까운 값을 쓰면 됩니다.", 13, MUT, false));
        eqView = new EqView(this);
        LinearLayout.LayoutParams eqLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eqLp.topMargin = px(12);
        eq9Box.addView(eqView, eqLp);
        eqNote = text("", 13, MUT, false);
        eqNote.setLineSpacing(0, 1.3f);
        eqNote.setPadding(0, px(6), 0, 0);
        eq9Box.addView(eqNote);
        c.addView(eq9Box);
        setEqMode(store.getBoolean("eq3", true));
    }

    private void setEqMode(boolean three) {
        store.edit().putBoolean("eq3", three).apply();
        eq3Box.setVisibility(three ? View.VISIBLE : View.GONE);
        eq9Box.setVisibility(three ? View.GONE : View.VISIBLE);
        for (int i = 0; i < 2; i++) {
            Button b = i == 0 ? eqTab3 : eqTab9;
            boolean on = (i == 0) == three;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(10));
            g.setColor(on ? INK : CARD);
            g.setStroke(px(1), on ? INK : LINE);
            b.setBackground(g);
            b.setTextColor(on ? 0xFFFFFFFF : INK);
        }
    }

    private boolean learned() {
        return knobShape[0] != null && knobShape[1] != null && knobShape[2] != null;
    }

    private double[][] knobShapes() {
        if (learned()) return knobShape;
        return new double[][]{Dsp.genericKnob(0), Dsp.genericKnob(1), Dsp.genericKnob(2)};
    }

    private void renderKnobs(Dsp.Result r) {
        knobRow.removeAllViews();
        Dsp.KnobFit kf = r == null ? null : Dsp.fitKnobs(r, knobShapes(), learned() ? 10 : 6);
        lastKnob = kf;
        for (int k = 0; k < 3; k++) {
            int st = kf == null ? 0 : kf.steps[k];
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER_HORIZONTAL);
            b.setPadding(px(6), px(12), px(6), px(12));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(12));
            g.setColor(kf == null || st == 0 ? BG : st > 0 ? 0xFFEAF0FB : 0xFFFFF1EF);
            g.setStroke(px(1), LINE);
            b.setBackground(g);
            TextView name = text(Dsp.KNOBS[k], 14, MUT, true);
            name.setGravity(Gravity.CENTER);
            b.addView(name);
            TextView v = text(kf == null ? "–" : signed(st), 34, kf == null ? MUT : st > 0 ? EqView.UP : st < 0 ? EqView.DOWN : INK, true);
            v.setGravity(Gravity.CENTER);
            b.addView(v);
            TextView a = text(kf == null ? "측정 후 표시" : st > 0 ? st + "단계 올리기" : st < 0 ? (-st) + "단계 내리기" : "그대로", 13, MUT, false);
            a.setGravity(Gravity.CENTER);
            b.addView(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (k < 2) lp.rightMargin = px(8);
            knobRow.addView(b, lp);
        }
        if (kf == null) knobNote.setText("측정하면 저음·중음·고음 노브를 몇 단계 움직일지 알려드립니다.");
        else knobNote.setText((learned() ? "학습한 내 차 노브 기준으로 계산했습니다. "
                : "일반적인 노브 형태로 추정한 값입니다(1단계 ≈ 1dB 가정). 아래 노브 학습을 하면 내 차에 정확히 맞춰집니다. ")
                + "예상 효과: 목표와의 차이 ±" + f1(kf.rmsBefore) + "dB → ±" + f1(kf.rmsAfter) + "dB");
    }

    // ---------- 노브 학습 ----------

    private void updateLearnUi() {
        String[] texts = {
                "저음·중음·고음 노브를 한 단계 올렸을 때 실제로 어떤 소리가 얼마나 바뀌는지 측정해, 추천을 내 차에 맞춥니다. 측정 4번(약 1분), 휴대폰을 한 자리에 고정해 두세요.",
                "차량 EQ의 저음·중음·고음을 모두 0으로 맞추고 버튼을 누르세요.",
                "저음만 +5로 올리고 버튼을 누르세요.",
                "저음을 0으로 되돌리고, 중음만 +5로 올린 뒤 누르세요.",
                "중음을 0으로 되돌리고, 고음만 +5로 올린 뒤 누르세요."};
        String[] btn = {"노브 학습 시작", "① 기준 측정 (모두 0)", "② 저음 +5 측정", "③ 중음 +5 측정", "④ 고음 +5 측정"};
        learnText.setText(texts[learnStep]);
        learnBtn.setText(btn[learnStep]);
        styleButton(learnBtn, learnStep > 0);
        if (learnStep > 0) {
            learnClear.setText("학습 취소");
            learnClear.setVisibility(View.VISIBLE);
        } else if (learned()) {
            learnClear.setText("학습 결과 지우기 (일반형으로 되돌리기)");
            learnClear.setVisibility(View.VISIBLE);
        } else {
            learnClear.setVisibility(View.GONE);
        }
        if (learnStep == 0 && learned() && learnStatus.getText().length() == 0) setLearnStatus("✓ 학습 완료 · 추천값이 내 차 노브 기준으로 계산됩니다", OK);
    }

    private void setLearnStatus(String s, int color) {
        learnStatus.setText(s);
        learnStatus.setTextColor(color);
    }

    private void onLearn() {
        if (busy) return;
        if (learnStep == 0) {
            learnStep = 1;
            learnTmp = new double[3][];
            setLearnStatus("", OK);
            updateLearnUi();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3);
            return;
        }
        startLearnMeasure();
    }

    private void onLearnClear() {
        if (busy) return;
        if (learnStep > 0) {
            learnStep = 0;
            setLearnStatus("학습을 취소했습니다. 차량 EQ 노브를 원래대로 되돌리세요.", MUT);
        } else {
            for (int k = 0; k < 3; k++) { knobShape[k] = null; store.edit().remove("knob" + k).apply(); }
            setLearnStatus("학습 결과를 지웠습니다. 일반적인 노브 형태로 추천합니다.", MUT);
            renderKnobs(lastResult);
        }
        updateLearnUi();
    }

    private void startLearnMeasure() {
        final int step = learnStep;
        setBusy(true);
        setLearnStatus("측정 준비 중…", INK);
        new Thread(() -> {
            try {
                float[] rec = AudioEngine.record(this, false, msg -> ui.post(() -> setLearnStatus(msg, INK)));
                if (rec.length < AudioEngine.FS * 9) throw new IllegalStateException("녹음이 너무 짧습니다.");
                ui.post(() -> setLearnStatus("분석 중…", INK));
                Dsp.Meas m = Dsp.measure(rec, AudioEngine.FS, false);
                ui.post(() -> {
                    setBusy(false);
                    if (m.ch[0].snr < 20) {
                        setLearnStatus("신호가 약해 다시 측정해 주세요 (신뢰도 " + Math.round(m.ch[0].snr) + "dB).", ACC);
                        return;
                    }
                    if (step == 1) {
                        learnBase = m.ch[0].rawDb;
                        learnStep = 2;
                        setLearnStatus("기준 측정 완료 · 다음 단계를 진행하세요.", OK);
                    } else {
                        learnTmp[step - 2] = Dsp.learnKnob(learnBase, m.ch[0].rawDb, 5);
                        if (step < 4) {
                            learnStep = step + 1;
                            setLearnStatus(Dsp.KNOBS[step - 2] + " 학습 완료 · 다음 단계를 진행하세요.", OK);
                        } else {
                            for (int k = 0; k < 3; k++) knobShape[k] = learnTmp[k];
                            saveKnobs();
                            learnStep = 0;
                            setLearnStatus("✓ 학습 완료! 고음 노브를 0으로 되돌리세요. 이제 추천값이 내 차 노브 기준으로 계산됩니다.", OK);
                            renderKnobs(lastResult);
                        }
                    }
                    updateLearnUi();
                });
            } catch (Throwable e) {
                String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다." : String.valueOf(e.getMessage());
                ui.post(() -> { setBusy(false); setLearnStatus("측정 실패: " + msg, ACC); });
            }
        }).start();
    }

    private void saveKnobs() {
        SharedPreferences.Editor e = store.edit();
        for (int k = 0; k < 3; k++) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < Dsp.NF; i++) {
                if (i > 0) s.append(',');
                s.append(String.format(Locale.US, "%.3f", knobShape[k][i]));
            }
            e.putString("knob" + k, s.toString());
        }
        e.apply();
    }

    private void loadKnobs() {
        try {
            for (int k = 0; k < 3; k++) {
                String s = store.getString("knob" + k, "");
                if (s.isEmpty()) { knobShape[k] = null; continue; }
                String[] f = s.split(",");
                if (f.length != Dsp.NF) { knobShape[k] = null; continue; }
                double[] v = new double[Dsp.NF];
                for (int i = 0; i < Dsp.NF; i++) v[i] = Double.parseDouble(f[i]);
                knobShape[k] = v;
            }
        } catch (RuntimeException e) {
            for (int k = 0; k < 3; k++) knobShape[k] = null;
        }
    }

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
        if (ok && code == 3) startLearnMeasure();
        else if (ok && code == 2) startDiag();
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
        learnBtn.setEnabled(!b);
        learnClear.setEnabled(!b);
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
        detailBox.removeAllViews();
        detailBox.addView(text("2단계 측정을 하면 항목별로 쉽게 풀어서 보여드립니다.", 14, MUT, false));
        eqView.setGains(null);
        eqNote.setText("");
        renderKnobs(null);
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

        renderDetails(r);
        eqView.setGains(r.eqGain);
        setEqNote(r.eqGain);
        renderKnobs(r);
    }

    private void oldDetails(Dsp.Result r) {
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
        if (lastKnob != null) s.append("3밴드 추천(").append(learned() ? "학습값" : "일반형 추정").append("): 저음 ").append(signed(lastKnob.steps[0]))
                .append(", 중음 ").append(signed(lastKnob.steps[1])).append(", 고음 ").append(signed(lastKnob.steps[2]))
                .append(String.format(Locale.US, " (목표와의 차이 ±%.1f → ±%.1fdB)\n", lastKnob.rmsBefore, lastKnob.rmsAfter));

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
