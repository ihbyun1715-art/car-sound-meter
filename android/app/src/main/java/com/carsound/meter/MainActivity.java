package com.carsound.meter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static final int BG = 0xFFF3F3F1, CARD = 0xFFFFFFFF, INK = 0xFF15171A, MUT = 0xFF5F646B, LINE = 0xFFDEDCD7, ACC = 0xFFFF5A4E;
    static final int OK = 0xFF1D7A4A, WARN = 0xFFB7791F, BLUE = 0xFF2F66D0;
    static final double TOL = 2.5;

    private ScrollView sv;
    private SharedPreferences store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float dp;
    private boolean busy;

    // 단계 표시
    private final TextView[] stepBadge = new TextView[3];

    // 1단계: 입력 진단
    private TextView diagStatus, diagSummary;
    private Button diagBtn, diagMore;
    private LinearLayout diagList;
    private boolean diagExpanded, hfUnreliable = true, lfUnreliable, chainComp;

    // 2단계: 노브 학습
    private TextView learnText, learnStatus;
    private Button learnBtn, learnClear;
    private KnobView knobView;
    private LinearLayout learnInfo;
    private int learnStep = 0;
    private double[] learnBase;
    private double[][] learnTmp = new double[3][];
    private final double[][] knobShape = new double[3][];

    // 3단계: 측정
    private final int[] cur = new int[3];
    private final TextView[] curVals = new TextView[3];
    private RadioButton modeLr;
    private Button run, reset;
    private TextView status;
    private final List<Dsp.Meas> session = new ArrayList<>();
    private long sessionId = 0;

    // 결과
    private LinearLayout resultCard, detailBox, eq3Box, eq9Box, knobRow, adviceBox, historyBox;
    private FrView chart;
    private EqView eqView;
    private TextView score, grade, count, legend, bandNote, eqNote, knobNote, info, shareNote, compareNote, prefHint;
    private Button eqTab3, eqTab9;
    private Dsp.Result lastResult;
    private Dsp.KnobFit lastKnob;
    private long compareId = -1;

    // 취향
    private Dsp.Pref pref = Dsp.PRESETS[0];
    private int presetIdx = 0;
    private boolean syncing;
    private final List<Button> chips = new ArrayList<>();
    private final SeekBar[] sliders = new SeekBar[4];
    private final TextView[] sliderVals = new TextView[4];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        store = getSharedPreferences("taste", MODE_PRIVATE);
        loadPref();
        loadKnobs();
        AudioEngine.chosenSource = store.getInt("source", -1);
        hfUnreliable = store.getBoolean("hfU", true);
        lfUnreliable = store.getBoolean("lfU", false);
        chainComp = store.getBoolean("chainC", false);
        for (int k = 0; k < 3; k++) cur[k] = store.getInt("cur" + k, 0);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = px(16);
        root.setPadding(pad, pad, pad, px(32));
        root.setBackgroundColor(BG);
        root.addView(text("차량 사운드 측정기", 28, INK, true));
        root.addView(text("① 입력 진단 → ② 노브 학습 → ③ 측정 순서로 진행하세요.", 14, MUT, false));

        buildStep1(root);
        buildStep2(root);
        buildStep3(root);
        buildResultCard(root);
        buildTasteCard(root);
        LinearLayout c4 = card(root);
        c4.addView(text("세부 결과", 18, INK, true));
        detailBox = vbox();
        c4.addView(detailBox);
        buildEqCard(root);
        LinearLayout ca = card(root);
        ca.addView(text("개선 방법", 18, INK, true));
        ca.addView(text("측정 결과에서 아쉬운 부분을 채우는 방법을 돈이 안 드는 것부터 순서대로 정리했습니다.", 13, MUT, false));
        adviceBox = vbox();
        ca.addView(adviceBox);
        buildHistoryCard(root);
        buildShareCard(root);
        LinearLayout c6 = card(root);
        c6.addView(text("측정 환경", 18, INK, true));
        info = text("", 14, MUT, false);
        info.setLineSpacing(0, 1.25f);
        c6.addView(info);
        LinearLayout c7 = card(root);
        c7.addView(text("목표 곡선의 근거", 18, INK, true));
        TextView src = text(
                "• 기본(표준) 곡선: 하만의 청취실 선호 응답에서 출발해, 차량 선호도 연구(Dennis, AES 2015: 평탄 대비 저음 +13dB, 고음 −2dB 선호)를 근사했습니다. 저음이 꺾이는 정확한 주파수는 공개 자료로 확인하지 못해 가정한 값입니다.\n"
                        + "• 표준·저음 강조·저음 절제: 하만 헤드폰 연구의 청취자 유형(64%·15%·21%)을 옮긴 것입니다. 차량에서 직접 검증된 값은 아닙니다.\n"
                        + "• 점수: 목표와의 차이의 들쭉날쭉함과 전체 기울기가 선호도를 좌우한다는 하만 예측 모델의 구조를 빌렸습니다. 차량용 계수는 아직 검증 전입니다.\n"
                        + "• 연구에서도 사람마다 차이가 컸습니다. 최종 판단은 귀로 하세요.",
                13, MUT, false);
        src.setLineSpacing(0, 1.3f);
        c7.addView(src);

        sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFitsSystemWindows(true);
        sv.addView(root);
        setContentView(sv);

        renderDiag(loadDiag(), AudioEngine.chosenSource);
        renderLearnInfo();
        updateLearnUi();
        applyPrefToUi();
        updateInfo(false);
        clearResult("");
        if (loadSession()) {
            render(Dsp.summarize(session, pref, opts()));
            setStatus("지난번 측정(" + session.size() + "곳)을 불러왔습니다. 이어서 측정하면 평균에 더해집니다.", false);
        }
        renderHistory();
        updateSteps();
    }

    // ---------- 진행 중인 측정 보관 (앱을 다시 켜도 결과·공유 유지) ----------

    private static String csv(double[] v) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) s.append(','); s.append(String.format(Locale.US, "%.3f", v[i])); }
        return s.toString();
    }

    private static double[] uncsv(String s) {
        String[] f = s.split(",");
        if (f.length != Dsp.NF) throw new IllegalArgumentException("len");
        double[] v = new double[Dsp.NF];
        for (int i = 0; i < Dsp.NF; i++) v[i] = Double.parseDouble(f[i]);
        return v;
    }

    private void saveSession() {
        try {
            JSONObject root = new JSONObject();
            root.put("id", sessionId);
            JSONArray ms = new JSONArray();
            for (Dsp.Meas m : session) {
                JSONObject o = new JSONObject();
                o.put("lr", m.lr);
                o.put("fs", m.fs);
                o.put("clip", m.clip);
                o.put("knobs", m.knobs[0] + "," + m.knobs[1] + "," + m.knobs[2]);
                JSONArray chs = new JSONArray();
                for (Dsp.Channel c : m.ch) {
                    JSONObject co = new JSONObject();
                    co.put("name", c.name);
                    co.put("p", c.p);
                    co.put("raw", c.raw);
                    co.put("E", c.E);
                    co.put("snr", c.snr);
                    co.put("thd", c.thd);
                    co.put("n", csv(c.n));
                    co.put("db", csv(c.rawDb));
                    chs.put(co);
                }
                o.put("ch", chs);
                ms.put(o);
            }
            root.put("ms", ms);
            store.edit().putString("session", root.toString()).apply();
        } catch (Exception ignored) { }
    }

    private boolean loadSession() {
        String s = store.getString("session", "");
        if (s.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(s);
            JSONArray ms = root.getJSONArray("ms");
            List<Dsp.Meas> out = new ArrayList<>();
            for (int i = 0; i < ms.length(); i++) {
                JSONObject o = ms.getJSONObject(i);
                Dsp.Meas m = new Dsp.Meas();
                m.lr = o.getBoolean("lr");
                m.fs = o.getInt("fs");
                m.clip = o.getDouble("clip");
                String[] k = o.getString("knobs").split(",");
                for (int j = 0; j < 3; j++) m.knobs[j] = Integer.parseInt(k[j]);
                JSONArray chs = o.getJSONArray("ch");
                m.ch = new Dsp.Channel[chs.length()];
                for (int j = 0; j < chs.length(); j++) {
                    JSONObject co = chs.getJSONObject(j);
                    Dsp.Channel c = new Dsp.Channel(co.getString("name"), co.getInt("p"), co.getInt("raw"));
                    c.E = co.getDouble("E");
                    c.snr = co.getDouble("snr");
                    c.thd = co.getDouble("thd");
                    c.n = uncsv(co.getString("n"));
                    c.rawDb = uncsv(co.getString("db"));
                    m.ch[j] = c;
                }
                out.add(m);
            }
            if (out.isEmpty()) return false;
            modeLr.setChecked(out.get(0).lr);
            session.clear();
            session.addAll(out);
            sessionId = root.getLong("id");
            return true;
        } catch (Exception e) {
            store.edit().remove("session").apply();
            return false;
        }
    }

    // ================= 단계 카드 =================

    private View stepHeader(int idx, String num, String title) {
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
        h.addView(text(title, 19, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView badge = text("", 12, 0xFFFFFFFF, true);
        badge.setPadding(px(10), px(3), px(10), px(3));
        stepBadge[idx] = badge;
        h.addView(badge);
        return h;
    }

    private void setBadge(int i, String s, int color) {
        TextView t = stepBadge[i];
        if (s.isEmpty()) { t.setVisibility(View.GONE); return; }
        t.setVisibility(View.VISIBLE);
        t.setText(s);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(20));
        g.setColor(color);
        t.setBackground(g);
    }

    private void updateSteps() {
        boolean d1 = AudioEngine.chosenSource >= 0, d2 = learned(), d3 = !session.isEmpty();
        int next = !d1 ? 0 : (!d2 && learnStep == 0 && !d3) ? 1 : 2;
        setBadge(0, d1 ? "✓ 완료" : "지금 할 단계", d1 ? OK : ACC);
        setBadge(1, d2 ? "✓ 완료" : next == 1 ? "다음 단계 · 선택" : learnStep > 0 ? "진행 중" : "선택", d2 ? OK : next == 1 || learnStep > 0 ? ACC : MUT);
        setBadge(2, d3 ? "✓ 측정 " + session.size() + "곳" : next == 2 ? "지금 할 단계" : "", d3 ? OK : ACC);
        diagBtn.setText(d1 ? "다시 진단 (약 1분 30초)" : "입력 진단 시작 (약 1분 30초)");
        styleButton(diagBtn, !d1);
        run.setText(d1 ? "측정 시작" : "진단 없이 측정");
        styleButton(run, d1 && learnStep == 0);
        styleButton(learnBtn, learnStep > 0 || (d1 && !d2 && !d3));
    }

    // ---------- 1단계 ----------

    private void buildStep1(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(stepHeader(0, "1", "입력 진단 · 녹음 방식 고르기"));
        c.addView(text("휴대폰의 녹음 방식 5가지로 같은 신호를 녹음해, 여러 방식의 공통값에 가장 가까운 방식을 고르고 휴대폰 마이크로 믿기 어려운 대역을 찾아냅니다.", 14, MUT, false));
        TextView chk = text("시작 전 확인\n"
                + "① 휴대폰 설정 > 소리 및 진동 > 음질 및 음향 효과에서 돌비 애트모스·이퀄라이저 끄기\n"
                + "② 휴대폰 미디어 볼륨은 높게, 실제 음량은 차량 볼륨으로\n"
                + "③ 차량 EQ 노브는 모두 0, 음장 효과는 평소 쓰는 상태로\n"
                + "④ 휴대폰을 운전석 귀 높이에 고정 (진단 중 움직이지 않기)", 13, INK, false);
        chk.setLineSpacing(0, 1.3f);
        chk.setPadding(px(12), px(10), px(12), px(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(BG);
        chk.setBackground(g);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = px(8);
        c.addView(chk, cl);
        diagStatus = text("", 15, INK, true);
        diagStatus.setPadding(0, px(10), 0, 0);
        c.addView(diagStatus);
        diagBtn = button("", true);
        diagBtn.setOnClickListener(v -> onDiag());
        c.addView(diagBtn, lp(px(52), px(10)));
        diagSummary = text("", 13, INK, false);
        diagSummary.setLineSpacing(0, 1.3f);
        diagSummary.setPadding(0, px(8), 0, 0);
        c.addView(diagSummary);
        diagMore = button("방식별 결과 자세히 보기", false);
        diagMore.setTextSize(13);
        diagMore.setOnClickListener(v -> { diagExpanded = !diagExpanded; diagList.setVisibility(diagExpanded ? View.VISIBLE : View.GONE); diagMore.setText(diagExpanded ? "방식별 결과 접기" : "방식별 결과 자세히 보기"); });
        c.addView(diagMore, lp(px(40), px(8)));
        diagList = vbox();
        diagList.setVisibility(View.GONE);
        c.addView(diagList);
    }

    // ---------- 2단계 ----------

    private void buildStep2(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(stepHeader(1, "2", "노브 학습"));
        c.addView(text("차량 EQ의 저음·중음·고음 노브를 한 단계 올리면 실제로 어떤 소리가 얼마나 바뀌는지 측정합니다. 한 번만 하면 되고, 하지 않으면 일반적인 노브 형태로 추정합니다.", 14, MUT, false));
        learnText = text("", 14, INK, true);
        learnText.setLineSpacing(0, 1.25f);
        learnText.setPadding(0, px(10), 0, 0);
        c.addView(learnText);
        learnBtn = button("", false);
        learnBtn.setOnClickListener(v -> onLearn());
        c.addView(learnBtn, lp(px(50), px(10)));
        learnClear = button("", false);
        learnClear.setTextSize(13);
        learnClear.setOnClickListener(v -> onLearnClear());
        c.addView(learnClear, lp(px(40), px(6)));
        learnStatus = text("", 13, OK, true);
        learnStatus.setPadding(0, px(8), 0, 0);
        c.addView(learnStatus);
        learnInfo = vbox();
        c.addView(learnInfo);
    }

    // ---------- 3단계 ----------

    private void buildStep3(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(stepHeader(2, "3", "측정"));
        c.addView(text("지금 차량 EQ에 설정된 노브 값을 맞춰 주세요. 추천은 이 값을 기준으로 '최종 설정값'으로 알려드립니다.", 14, MUT, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(10), 0, 0);
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            LinearLayout col = vbox();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView name = text("지금 " + Dsp.KNOBS[k], 13, MUT, true);
            name.setGravity(Gravity.CENTER);
            col.addView(name);
            LinearLayout st = new LinearLayout(this);
            st.setOrientation(LinearLayout.HORIZONTAL);
            st.setGravity(Gravity.CENTER_VERTICAL);
            Button minus = button("−", false);
            minus.setPadding(0, 0, 0, 0);
            minus.setOnClickListener(v -> changeKnob(kk, -1));
            Button plus = button("+", false);
            plus.setPadding(0, 0, 0, 0);
            plus.setOnClickListener(v -> changeKnob(kk, 1));
            curVals[k] = text(signed(cur[k]), 20, INK, true);
            curVals[k].setGravity(Gravity.CENTER);
            st.addView(minus, new LinearLayout.LayoutParams(px(36), px(40)));
            st.addView(curVals[k], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            st.addView(plus, new LinearLayout.LayoutParams(px(36), px(40)));
            col.addView(st, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (k < 2) l.rightMargin = px(8);
            row.addView(col, l);
        }
        c.addView(row);
        c.addView(text("머리 주변 30cm 안에서 위치를 조금씩 옮겨 4~6번 측정하면 자동으로 평균을 냅니다.", 13, MUT, false));
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        rg.setPadding(0, px(6), 0, 0);
        RadioButton stereo = new RadioButton(this);
        stereo.setText("스테레오 (약 14초)");
        stereo.setId(View.generateViewId());
        modeLr = new RadioButton(this);
        modeLr.setText("좌·우 분리 (약 24초)");
        modeLr.setId(View.generateViewId());
        rg.addView(stereo);
        rg.addView(modeLr);
        stereo.setChecked(true);
        rg.setOnCheckedChangeListener((gr, id) -> {
            if (!busy && !session.isEmpty()) newSession("측정 방식을 바꿔서 새로 측정을 시작합니다.");
        });
        c.addView(rg);
        run = button("", true);
        run.setOnClickListener(v -> onRun());
        c.addView(run, lp(px(56), px(10)));
        reset = button("새로 시작 (평균 지우기)", false);
        reset.setOnClickListener(v -> { if (!busy) newSession("측정을 새로 시작합니다. 이전 측정은 기록에 남아 있습니다."); });
        c.addView(reset, lp(px(46), px(8)));
        status = text("", 15, INK, true);
        status.setPadding(0, px(10), 0, 0);
        c.addView(status);
    }

    private void changeKnob(int k, int d) {
        if (busy) return;
        int v = Math.max(-10, Math.min(10, cur[k] + d));
        if (v == cur[k]) return;
        cur[k] = v;
        curVals[k].setText(signed(v));
        store.edit().putInt("cur" + k, v).apply();
        if (!session.isEmpty() && !learned()) newSession("노브 값이 바뀌어 새로 측정을 시작합니다. 노브 학습을 하면 노브를 바꿔도 이전 측정을 함께 활용할 수 있습니다.");
        else rescore();
    }

    private void newSession(String msg) {
        session.clear();
        sessionId = 0;
        store.edit().remove("session").apply();
        clearResult(msg);
        updateSteps();
    }

    // ================= 입력 진단 =================

    static final class DiagItem {
        final int src;
        final Dsp.Diag d;
        DiagItem(int src, Dsp.Diag d) { this.src = src; this.d = d; }
        boolean valid() { return d != null && d.snr >= 20; }
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
        diagSummary.setText("");
        setDiagStatus("진단 준비 중…", INK);
        new Thread(() -> {
            int[] srcs = AudioEngine.candidateSources();
            List<DiagItem> items = new ArrayList<>();
            try {
                for (int i = 0; i < srcs.length; i++) {
                    String label = (i + 1) + "/" + srcs.length + " '" + AudioEngine.sourceName(srcs[i]) + "' 방식 · ";
                    ui.post(() -> setDiagStatus(label + "준비 중…", INK));
                    float[] rec = AudioEngine.recordWith(this, srcs[i], Dsp.diagStimulus(AudioEngine.FS), label + "녹음 중… ", msg -> ui.post(() -> setDiagStatus(msg, INK)));
                    if (rec == null) { items.add(new DiagItem(srcs[i], null)); continue; }
                    ui.post(() -> setDiagStatus(label + "분석 중…", INK));
                    items.add(new DiagItem(srcs[i], Dsp.diagnose(rec, AudioEngine.FS)));
                    Thread.sleep(400);
                }
                int chosen = chooseSource(items);
                saveDiag(items);
                ui.post(() -> {
                    setBusy(false);
                    if (chosen >= 0) {
                        AudioEngine.chosenSource = chosen;
                        store.edit().putInt("source", chosen).putBoolean("hfU", hfUnreliable).putBoolean("lfU", lfUnreliable).putBoolean("chainC", chainComp).apply();
                        newSession("");
                    }
                    renderDiag(items, chosen);
                    if (chosen < 0) setDiagStatus("진단 실패: 테스트 신호가 제대로 녹음되지 않았습니다. 블루투스 '미디어 오디오' 연결과 볼륨을 확인하고 다시 진단해 주세요.", ACC);
                    updateInfo(true);
                    updateSteps();
                });
            } catch (Throwable e) {
                String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다." : String.valueOf(e.getMessage());
                ui.post(() -> { setBusy(false); renderDiag(items, -1); setDiagStatus("진단 실패: " + msg, ACC); updateSteps(); });
            }
        }).start();
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static final int UNPROCESSED = 9;

    /** 공통값 계산에 쓸 방식: 음량 자동 조절이 없는 방식이 2개 이상이면 그것만, 아니면 유효한 방식 전부 */
    static List<DiagItem> pool(List<DiagItem> items) {
        List<DiagItem> v = new ArrayList<>(), c = new ArrayList<>();
        for (DiagItem it : items) if (it.valid()) { v.add(it); if (it.d.linErr <= 1.5) c.add(it); }
        return c.size() >= 2 ? c : v;
    }

    /** 공통값(중앙값)에 가장 가까운 방식을 고르고, 방식끼리 크게 다른 대역을 '신뢰 낮음'으로 표시한다. 가공 없음이 공통값과 맞으면 우선한다 */
    private int chooseSource(List<DiagItem> items) {
        List<DiagItem> p = pool(items);
        if (p.isEmpty()) return -1;
        List<Double> lows = new ArrayList<>(), highs = new ArrayList<>();
        for (DiagItem it : p) { lows.add(it.d.lowRel); highs.add(it.d.highRel); }
        double ml = median(lows), mh = median(highs);
        int best = -1;
        double bs = 1e9;
        for (DiagItem it : p) {
            double s = Math.abs(it.d.lowRel - ml) + Math.abs(it.d.highRel - mh) + 0.3 * it.d.linErr;
            if (it.src == UNPROCESSED && Math.abs(it.d.lowRel - ml) <= 3 && Math.abs(it.d.highRel - mh) <= 3) s = -1;
            if (s < bs) { bs = s; best = it.src; }
        }
        int nv = 0;
        boolean anyClean = false;
        for (DiagItem it : items) if (it.valid()) { nv++; if (it.d.linErr <= 1.5) anyClean = true; }
        hfUnreliable = p.size() < 2 || Collections.max(highs) - Collections.min(highs) > 6;
        lfUnreliable = p.size() >= 2 && Collections.max(lows) - Collections.min(lows) > 6;
        chainComp = nv >= 2 && !anyClean;
        return best;
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
        boolean done = AudioEngine.chosenSource >= 0;
        if (done) setDiagStatus("✓ '" + AudioEngine.sourceName(AudioEngine.chosenSource) + "' 방식으로 측정합니다", OK);
        else setDiagStatus("아직 진단하지 않았습니다. 측정 전에 먼저 진단해 주세요.", ACC);
        diagMore.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (items.isEmpty()) { diagSummary.setText(""); return; }
        List<DiagItem> p = pool(items);
        List<Double> lows = new ArrayList<>(), highs = new ArrayList<>();
        for (DiagItem it : p) { lows.add(it.d.lowRel); highs.add(it.d.highRel); }
        double ml = median(lows), mh = median(highs);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        StringBuilder excl = new StringBuilder();
        for (DiagItem it : items) if (it.valid() && !p.contains(it)) excl.append(excl.length() > 0 ? "·" : "").append(AudioEngine.sourceName(it.src));
        if (excl.length() > 0) {
            append(sb, "• " + excl + "  ", MUT);
            sb.append("음량 자동 조절(가공)이 있어 비교에서 뺐습니다.\n");
        }
        if (!highs.isEmpty()) {
            double hs = Collections.max(highs) - Collections.min(highs);
            if (hfUnreliable) {
                append(sb, "⚠ 고음(5kHz 이상) 신뢰 낮음  ", WARN);
                sb.append(highs.size() < 2 ? "비교할 방식이 부족해 확인하지 못했습니다." : "녹음 방식마다 " + f1(hs) + "dB 다르게 잡힙니다.");
                sb.append(" 점수와 노브 추천에서 제외합니다.\n");
            } else {
                append(sb, "✓ 고음  ", OK);
                sb.append("방식끼리 차이 " + f1(hs) + "dB로 일관됩니다.\n");
            }
            double ls = Collections.max(lows) - Collections.min(lows);
            if (lfUnreliable) {
                append(sb, "⚠ 저음(80Hz 이하) 신뢰 낮음  ", WARN);
                sb.append("녹음 방식마다 " + f1(ls) + "dB 다르게 잡힙니다.\n");
            }
            if (chainComp) {
                append(sb, "⚠ 재생 경로의 음량 자동 조절  ", WARN);
                sb.append("모든 방식에서 작은 소리가 커져서 녹음됐습니다. 마이크가 아니라 휴대폰 음향 효과(돌비 애트모스 등)나 차량의 자동 음량 기능 때문일 수 있습니다. 측정은 항상 같은 볼륨으로 하세요.");
            }
        }
        diagSummary.setText(sb);
        for (DiagItem it : items) {
            boolean rec = it.src == chosen || (chosen < 0 && it.src == AudioEngine.chosenSource);
            boolean out = it.valid() && !p.contains(it);
            LinearLayout box = vbox();
            box.setPadding(px(12), px(10), px(12), px(10));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(12));
            g.setColor(rec ? 0xFFFFF1EF : it.d == null ? BG : CARD);
            g.setStroke(px(rec ? 2 : 1), rec ? ACC : LINE);
            box.setBackground(g);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(AudioEngine.sourceName(it.src), 16, it.d == null ? MUT : INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (rec) head.addView(pill("선택 · 사용 중", ACC));
            else if (out) head.addView(pill("가공 감지 · 제외", WARN));
            box.addView(head);
            if (it.d == null) box.addView(text("이 기기에서 쓸 수 없는 방식", 13, MUT, false));
            else if (!it.valid()) box.addView(metric("결과", "무효", "신호가 제대로 녹음되지 않음", ACC));
            else {
                boolean agc = it.d.linErr > 1.5;
                box.addView(metric("음량 자동 조절", agc ? "감지됨" : "없음", String.format(Locale.US, "오차 %.1fdB", it.d.linErr), agc ? WARN : OK));
                double dl = it.d.lowRel - ml, dh = it.d.highRel - mh;
                box.addView(metric("저음 (공통값 대비)", (dl >= 0 ? "+" : "") + f1(dl) + "dB", null, Math.abs(dl) <= 2 ? OK : Math.abs(dl) <= 5 ? WARN : ACC));
                box.addView(metric("고음 (공통값 대비)", (dh >= 0 ? "+" : "") + f1(dh) + "dB", null, Math.abs(dh) <= 2 ? OK : Math.abs(dh) <= 5 ? WARN : ACC));
                int sc = it.d.snr >= 40 ? OK : it.d.snr >= 25 ? WARN : ACC;
                box.addView(metric("신뢰도", it.d.snr >= 40 ? "높음" : it.d.snr >= 25 ? "보통" : "낮음", Math.round(it.d.snr) + "dB", sc));
            }
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l.topMargin = px(8);
            diagList.addView(box, l);
        }
        TextView note = text("공통값은 가공이 없는 방식들의 중앙값입니다. '가공 없음'이 공통값과 3dB 안으로 맞으면 우선 선택합니다. 한 방식만 저음이나 고음을 크게 다르게 잡는다면 그 방식이 소리를 가공하고 있다는 뜻입니다.", 12, MUT, false);
        note.setPadding(0, px(8), 0, 0);
        diagList.addView(note);
    }

    // ================= 노브 학습 =================

    private boolean learned() {
        return knobShape[0] != null && knobShape[1] != null && knobShape[2] != null;
    }

    private double[][] knobShapes() {
        if (learned()) return knobShape;
        return new double[][]{Dsp.genericKnob(0), Dsp.genericKnob(1), Dsp.genericKnob(2)};
    }

    private void updateLearnUi() {
        String[] texts = {
                learned() ? "학습한 노브 특성이 저장되어 있습니다. 휴대폰이나 차량 오디오 장비를 바꿨다면 다시 학습하세요."
                        : "측정 4번(약 1분)이 필요합니다. 휴대폰을 거치대 등에 고정하고, 정차한 조용한 상태에서 평소 볼륨으로 진행하세요.",
                "① 차량 EQ의 저음·중음·고음을 모두 0으로 맞추고 버튼을 누르세요.",
                "② 저음만 +5로 올리고 버튼을 누르세요.",
                "③ 저음을 0으로 되돌리고, 중음만 +5로 올린 뒤 누르세요.",
                "④ 중음을 0으로 되돌리고, 고음만 +5로 올린 뒤 누르세요."};
        String[] btn = {learned() ? "다시 학습하기" : "노브 학습 시작", "기준 측정 (모두 0)", "저음 +5 측정", "중음 +5 측정", "고음 +5 측정"};
        learnText.setText(texts[learnStep]);
        learnBtn.setText(btn[learnStep]);
        if (learnStep > 0) {
            learnClear.setText("학습 취소");
            learnClear.setVisibility(View.VISIBLE);
        } else if (learned()) {
            learnClear.setText("학습 결과 지우기 (일반형으로 되돌리기)");
            learnClear.setVisibility(View.VISIBLE);
        } else learnClear.setVisibility(View.GONE);
    }

    private void setLearnStatus(String s, int color) {
        learnStatus.setText(s);
        learnStatus.setTextColor(color);
    }

    private void renderLearnInfo() {
        learnInfo.removeAllViews();
        if (!learned()) return;
        TextView h = text("학습 결과: 노브 1단계를 올렸을 때 바뀌는 양", 14, INK, true);
        h.setPadding(0, px(12), 0, px(4));
        learnInfo.addView(h);
        knobView = new KnobView(this);
        knobView.setShapes(knobShape);
        learnInfo.addView(knobView);
        boolean allOk = true;
        for (int k = 0; k < 3; k++) {
            Dsp.KnobInfo ki = Dsp.knobInfo(knobShape[k], k);
            allOk &= ki.valid;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, px(8), 0, 0);
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(KnobView.COLORS[k]);
            dot.setBackground(d);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(10), px(10));
            dl.rightMargin = px(10);
            dl.topMargin = px(5);
            row.addView(dot, dl);
            LinearLayout col = vbox();
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(Dsp.KNOBS[k] + " 노브", 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(pill(ki.valid ? "정상" : "확인 필요", ki.valid ? OK : WARN));
            col.addView(head);
            col.addView(text("1단계당 최대 " + f1(ki.peak) + "dB · 주로 " + fmtF(ki.lo) + "~" + fmtF(ki.hi) + "에 작용", 13, MUT, false));
            if (!ki.valid) {
                TextView w = text(ki.problem, 13, WARN, false);
                w.setLineSpacing(0, 1.2f);
                col.addView(w);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            learnInfo.addView(row);
        }
        TextView n = text(allOk ? "세 노브 모두 담당 대역에서만 움직여 학습이 정상으로 보입니다."
                : "확인이 필요한 노브가 있습니다. 휴대폰을 고정하고 노브 외에는 아무것도 바꾸지 않은 채 다시 학습해 보세요.", 12, allOk ? OK : WARN, false);
        n.setPadding(0, px(8), 0, 0);
        learnInfo.addView(n);
    }

    private void onLearn() {
        if (busy) return;
        if (learnStep == 0) {
            learnStep = 1;
            learnTmp = new double[3][];
            setLearnStatus("", OK);
            updateLearnUi();
            updateSteps();
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
            renderLearnInfo();
            if (!session.isEmpty()) newSession("노브 학습 결과를 지워서 새로 측정을 시작합니다.");
        }
        updateLearnUi();
        updateSteps();
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
                            setLearnStatus(Dsp.KNOBS[step - 2] + " 측정 완료 · 다음 단계를 진행하세요.", OK);
                        } else {
                            for (int k = 0; k < 3; k++) knobShape[k] = learnTmp[k];
                            saveKnobs();
                            learnStep = 0;
                            for (int k = 0; k < 3; k++) { cur[k] = 0; curVals[k].setText("0"); store.edit().putInt("cur" + k, 0).apply(); }
                            setLearnStatus("✓ 학습 완료! 차량의 고음 노브를 0으로 되돌리세요. 3단계 측정의 노브 값도 0으로 맞춰 두었습니다.", OK);
                            renderLearnInfo();
                            if (!session.isEmpty()) rescore();
                        }
                    }
                    updateLearnUi();
                    updateSteps();
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

    // ================= 측정 =================

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
        for (Button x : new Button[]{run, reset, diagBtn, learnBtn, learnClear}) { x.setEnabled(!b); x.setAlpha(b ? 0.6f : 1f); }
        if (b) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void startMeasure() {
        final boolean lr = modeLr.isChecked();
        final int[] knobs = cur.clone();
        setBusy(true);
        setStatus("준비 중…", false);
        new Thread(() -> {
            try {
                float[] rec = AudioEngine.record(this, lr, msg -> ui.post(() -> setStatus(msg, false)));
                ui.post(() -> setStatus("분석 중…", false));
                if (rec.length < AudioEngine.FS * (lr ? 19 : 9)) throw new IllegalStateException("녹음이 너무 짧습니다. 다시 측정해 주세요.");
                Dsp.Meas m = Dsp.measure(rec, AudioEngine.FS, lr);
                m.knobs = knobs;
                ui.post(() -> {
                    session.add(m);
                    if (sessionId == 0) sessionId = System.currentTimeMillis();
                    render(Dsp.summarize(session, pref, opts()));
                    saveRecord();
                    saveSession();
                    setStatus("측정 완료 (" + session.size() + "곳, 기록에 저장됨) · 위치를 조금 옮겨 한 번 더 재면 평균이 더 정확해집니다", false);
                    setBusy(false);
                    updateInfo(true);
                    updateSteps();
                    sv.post(() -> sv.smoothScrollTo(0, resultCard.getTop() - px(12)));
                });
            } catch (Throwable e) {
                String msg = e instanceof OutOfMemoryError ? "메모리가 부족합니다. 다른 앱을 닫고 다시 시도해 주세요." : String.valueOf(e.getMessage());
                ui.post(() -> { setStatus("측정 실패: " + msg, true); setBusy(false); });
            }
        }).start();
    }

    private Dsp.Opts opts() {
        Dsp.Opts o = new Dsp.Opts();
        o.tol = TOL;
        o.hi = hfUnreliable ? 5000 : 10000;
        o.lo = lfUnreliable ? 80 : 50;
        o.shapes = learned() ? knobShape : null;
        o.cur = cur.clone();
        return o;
    }

    private void rescore() {
        chart.setPref(pref);
        chart.setBand(TOL, hfUnreliable ? 5000 : 20000);
        if (session.isEmpty() || busy) { setLegend(); updateCompare(); return; }
        render(Dsp.summarize(session, pref, opts()));
    }

    // ================= 결과 =================

    private void buildResultCard(LinearLayout root) {
        resultCard = card(root);
        resultCard.addView(text("측정 결과", 18, INK, true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(4), 0, px(6));
        score = text("--", 56, INK, true);
        row.addView(score);
        LinearLayout col = vbox();
        col.setPadding(px(14), 0, 0, 0);
        grade = text("측정 전", 18, INK, true);
        count = text("", 13, MUT, false);
        col.addView(grade);
        col.addView(count);
        col.addView(text("100점 만점 · 목표 범위 안에 들고, 한쪽으로 기울지 않고, 찌그러짐이 적을수록 높습니다", 12, MUT, false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        resultCard.addView(row);
        chart = new FrView(this);
        resultCard.addView(chart);
        legend = text("", 13, MUT, false);
        resultCard.addView(legend);
        bandNote = text("", 13, INK, false);
        bandNote.setLineSpacing(0, 1.25f);
        bandNote.setPadding(0, px(6), 0, 0);
        resultCard.addView(bandNote);
        compareNote = text("", 13, MUT, true);
        resultCard.addView(compareNote);
    }

    private void clearResult(String msg) {
        lastResult = null;
        lastKnob = null;
        chart.setResult(null);
        chart.setPref(pref);
        chart.setBand(TOL, hfUnreliable ? 5000 : 20000);
        setLegend();
        score.setText("--");
        grade.setText("측정 전");
        count.setText("3단계 측정을 하면 표시됩니다");
        bandNote.setText("");
        detailBox.removeAllViews();
        detailBox.addView(text("3단계 측정을 하면 항목별로 쉽게 풀어서 보여드립니다.", 14, MUT, false));
        eqView.setGains(null);
        eqNote.setText("");
        renderKnobs(null);
        updateCompare();
        if (msg != null) setStatus(msg, false);
    }

    private void render(Dsp.Result r) {
        lastResult = r;
        chart.setResult(r);
        chart.setPref(pref);
        chart.setBand(TOL, hfUnreliable ? 5000 : 20000);
        setLegend();
        int t = (int) Math.round(r.total);
        score.setText(String.valueOf(t));
        grade.setText(gradeName(t));
        count.setText("측정 " + r.count + "곳 평균 · " + pref.name + " 기준 · 지금 노브 " + knobStr(cur));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "목표 범위(±" + f1(TOL) + "dB) 안: " + Math.round(r.inBand) + "%", INK);
        if (hfUnreliable) sb.append("  ·  회색 영역(5kHz 이상)은 휴대폰 마이크로 믿기 어려워 점수에서 뺐습니다");
        if (r.converted) sb.append("\n노브 값이 다를 때 잰 측정은 학습한 노브 특성으로 지금 노브 기준으로 환산했습니다.");
        bandNote.setText(sb);
        renderDetails(r);
        eqView.setGains(r.eqGain);
        setEqNote(r.eqGain);
        renderKnobs(r);
        updateCompare();
    }

    static String gradeName(int t) {
        return t >= 85 ? "매우 좋음" : t >= 70 ? "좋음" : t >= 55 ? "보통" : "개선 필요";
    }

    private void setLegend() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "■ ", 0x661D7A4A);
        sb.append("목표 범위 ±" + f1(TOL) + "dB   ");
        append(sb, "┅ ", 0xFF8A8F96);
        sb.append("목표(" + pref.name + ")");
        if (lastResult != null) for (int i = 0; i < lastResult.ch.length; i++) {
            sb.append("   ");
            append(sb, "━ ", FrView.COLORS[i % FrView.COLORS.length]);
            sb.append(lastResult.ch[i].name);
        }
        if (compareId >= 0) {
            sb.append("   ");
            append(sb, "┄ ", 0xFF9AA0A8);
            sb.append("비교 기록");
        }
        legend.setText(sb);
    }

    // ---------- 세부 결과 ----------

    static final String[] LV = {"아주 좋음", "좋음", "보통", "아쉬움"};

    static int lvColor(int l) { return l <= 1 ? OK : l == 2 ? WARN : ACC; }

    private void renderDetails(Dsp.Result r) {
        detailBox.removeAllViews();
        List<String> warns = new ArrayList<>();
        if (AudioEngine.lastOutputIsPhoneSpeaker) warns.add("소리가 휴대폰 스피커로 나왔습니다. 차량에 연결한 뒤 다시 측정하세요.");
        if (AudioEngine.lastInputNotBuiltin) warns.add("내장 마이크가 아닌 다른 입력이 쓰였습니다. 측정 환경을 확인하세요.");
        if (r.clip > 0.98) warns.add("마이크 입력이 넘쳤습니다. 볼륨을 조금 낮추고 다시 재세요.");
        if (r.thd > 5) warns.add("찌그러짐이 " + f1(r.thd) + "%로 비정상적으로 큽니다. 볼륨이 너무 크거나 측정 중 휴대폰·물건이 떨렸을 수 있습니다. 볼륨을 낮추고 휴대폰을 고정한 뒤 다시 재세요.");
        if (r.snr < 20) warns.add("신호가 소음에 묻혀 결과를 믿기 어렵습니다. 볼륨을 올리거나 시동을 끄고 다시 재세요.");
        double maxSpread = 0;
        for (int i = 0; i < Dsp.NF; i++) if (Dsp.FR[i] >= 100 && Dsp.FR[i] <= 3000) maxSpread = Math.max(maxSpread, r.spread[i]);
        if (r.count > 1 && maxSpread > 10) warns.add("측정 위치마다 결과가 최대 " + Math.round(maxSpread) + "dB 달랐습니다. 머리 주변 30cm 안에서만 옮겨 다시 재 보세요.");
        for (String w : warns) detailBox.addView(banner("⚠ " + w));

        int tl = r.excess <= 0.5 ? 0 : r.excess <= 1.2 ? 1 : r.excess <= 2.5 ? 2 : 3;
        addMetric("음색 균형", LV[tl], lvColor(tl), r.tone,
                "목표 범위 안 " + Math.round(r.inBand) + "% · 범위를 벗어난 양 평균 " + f1(r.excess) + "dB",
                "저음·중음·고음이 목표 범위(±" + f1(TOL) + "dB) 안에 얼마나 들어오는지 봅니다. 범위 안의 작은 차이는 귀로 구분하기 어려워 감점하지 않고, 튀는 봉우리는 꺼진 골보다 무겁게 봅니다.");
        double at = Math.abs(r.tilt);
        int il = at <= 0.3 ? 0 : at <= 0.6 ? 1 : at <= 1.0 ? 2 : 3;
        addMetric("전체 기울기", LV[il], lvColor(il), r.tiltScore,
                at <= 0.3 ? "한쪽으로 기울지 않았습니다 (" + signedD(r.tilt) + "dB/옥타브)"
                        : (r.tilt > 0 ? "고음 쪽이 커서 밝고 날카로운 편" : "저음 쪽이 커서 어둡고 답답한 편") + " (" + signedD(r.tilt) + "dB/옥타브)\n" + tiltCause(r),
                "저음에서 고음까지 소리 전체가 한쪽으로 기울었는지 봅니다. 하만의 선호도 예측 모델에서도 이 기울기가 핵심 요인이었습니다. ±0.3dB/옥타브 이내면 균형이 좋습니다.");
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
                    "운전석은 왼쪽 스피커에 가까워 소리가 왼쪽으로 쏠리기 쉽습니다. 음량 차 1dB, 시간 차 1ms 이하가 이상적입니다.");
        }
        int sl = r.snr >= 40 ? 0 : r.snr >= 30 ? 1 : r.snr >= 20 ? 2 : 3;
        String[] SN = {"높음", "충분", "보통", "낮음"};
        addMetric("측정 신뢰도", SN[sl], lvColor(sl), -1, "테스트 신호가 주변 소음보다 " + Math.round(r.snr) + "dB 큼",
                "점수가 아니라 이번 측정을 얼마나 믿을 수 있는지입니다. 30dB 이상이면 충분하고, 20dB 미만이면 다시 측정하세요.");

        TextView h = text("눈에 띄는 구간", 16, INK, true);
        h.setPadding(0, px(16), 0, px(2));
        detailBox.addView(h);
        if (r.issues.isEmpty()) detailBox.addView(text("목표에서 크게(+5dB 또는 −6dB 넘게) 벗어난 구간이 없습니다.", 14, MUT, false));
        int k = 0;
        for (Dsp.Issue x : r.issues) {
            if (k++ >= 6) break;
            addIssue(x);
        }
        TextView g = text("그래프에서 빨간 선이 초록 띠(목표 범위)보다 위에 있으면 그 소리가 크게, 아래에 있으면 작게 들린다는 뜻입니다.", 12, MUT, false);
        g.setPadding(0, px(10), 0, 0);
        detailBox.addView(g);
    }

    /** 기울기의 원인: 대역별로 목표보다 얼마나 크거나 작은지 */
    private String tiltCause(Dsp.Result r) {
        double top = Math.min(r.opts.hi, 10000);
        double lo = Dsp.bandMean(r.dev, Math.max(50, r.opts.lo), 200), mid = Dsp.bandMean(r.dev, 300, 2000), hi = Dsp.bandMean(r.dev, 2000, top);
        String s = "목표 대비 저음역(~200Hz) " + signedD(lo) + "dB · 중역 " + signedD(mid) + "dB · 고음역(2k~" + fmtF(top) + ") " + signedD(hi) + "dB";
        if (Math.abs(r.tilt) > 0.3) {
            if (r.tilt < 0) s += lo > -hi ? "\n→ 주로 저음이 많아서 어둡습니다." : "\n→ 주로 고음이 부족해서 어둡습니다.";
            else s += hi > -lo ? "\n→ 주로 고음이 많아서 밝습니다." : "\n→ 주로 저음이 부족해서 밝습니다.";
        }
        return s;
    }

    private void addMetric(String title, String verdict, int color, double scoreV, String value, String meaning) {
        if (detailBox.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(LINE);
            detailBox.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        }
        LinearLayout box = vbox();
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
        if (scoreV >= 0) box.addView(meter(scoreV));
        TextView m = text(meaning, 13, MUT, false);
        m.setLineSpacing(0, 1.25f);
        m.setPadding(0, px(6), 0, 0);
        box.addView(m);
        detailBox.addView(box);
    }

    private View meter(double scoreV) {
        int sc = (int) Math.round(scoreV);
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
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = px(8);
        t.setLayoutParams(l);
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
        if (x.f < 46) why += " 40Hz 근처 값은 측정 오차일 수도 있습니다.";
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(10), 0, 0);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(up ? ACC : BLUE);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(10), px(10));
        dl.rightMargin = px(10);
        dl.topMargin = px(5);
        row.addView(dot, dl);
        LinearLayout col = vbox();
        col.addView(text(title, 14, INK, true));
        TextView w = text(why, 13, MUT, false);
        w.setLineSpacing(0, 1.25f);
        col.addView(w);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        detailBox.addView(row);
    }

    // ---------- EQ 제안 ----------

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

        eq3Box = vbox();
        knobRow = new LinearLayout(this);
        knobRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.topMargin = px(12);
        eq3Box.addView(knobRow, kl);
        knobNote = text("", 13, MUT, false);
        knobNote.setLineSpacing(0, 1.25f);
        knobNote.setPadding(0, px(8), 0, 0);
        eq3Box.addView(knobNote);
        c.addView(eq3Box);

        eq9Box = vbox();
        eq9Box.addView(text("지금 상태에서 각 대역을 막대만큼 올리거나 내려 보세요. 대역 이름이 조금 달라도 가장 가까운 값을 쓰면 됩니다.", 13, MUT, false));
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

    private void setEqNote(int[] g) {
        int mi = 0;
        for (int i = 0; i < g.length; i++) if (Math.abs(g[i]) > Math.abs(g[mi])) mi = i;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "■ ", EqView.UP);
        sb.append("올리기     ");
        append(sb, "■ ", EqView.DOWN);
        sb.append("내리기\n");
        if (g[mi] == 0) sb.append("목표 범위에 가깝습니다. 지금 EQ 설정을 그대로 두세요.");
        else sb.append("가장 먼저 손볼 대역: ").append(fmtF(Dsp.EQ_BANDS[mi])).append(" ").append(signed(g[mi])).append("dB");
        if (hfUnreliable) sb.append("\n8kHz·16kHz는 휴대폰 마이크로 믿기 어려워 제안하지 않습니다. 귀로 조정하세요.");
        eqNote.setText(sb);
    }

    private void renderKnobs(Dsp.Result r) {
        knobRow.removeAllViews();
        Dsp.KnobFit kf = r == null ? null : Dsp.fitKnobs(r, knobShapes(), learned(), 10);
        lastKnob = kf;
        boolean hold = kf != null && kf.hold();
        for (int k = 0; k < 3; k++) {
            int tg = kf == null ? 0 : hold ? kf.cur[k] : kf.target[k], now = cur[k], dlt = tg - now;
            boolean fixed = kf != null && k == 2 && kf.trebleFixed;
            LinearLayout b = vbox();
            b.setGravity(Gravity.CENTER_HORIZONTAL);
            b.setPadding(px(6), px(12), px(6), px(12));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(12));
            g.setColor(kf == null || dlt == 0 ? BG : dlt > 0 ? 0xFFEAF0FB : 0xFFFFF1EF);
            g.setStroke(px(1), LINE);
            b.setBackground(g);
            TextView name = text(Dsp.KNOBS[k] + " 권장값", 13, MUT, true);
            name.setGravity(Gravity.CENTER);
            b.addView(name);
            TextView v = text(kf == null ? "–" : fixed ? "귀로" : signed(tg), fixed ? 24 : 34, kf == null || fixed ? MUT : dlt > 0 ? EqView.UP : dlt < 0 ? EqView.DOWN : INK, true);
            v.setGravity(Gravity.CENTER);
            b.addView(v);
            String a = kf == null ? "측정 후 표시" : fixed ? "마이크로 판단 불가" : "지금 " + signed(now) + (dlt > 0 ? " → " + dlt + "단계 올리기" : dlt < 0 ? " → " + (-dlt) + "단계 내리기" : " · 그대로");
            TextView at = text(a, 12, MUT, false);
            at.setGravity(Gravity.CENTER);
            b.addView(at);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (k < 2) l.rightMargin = px(8);
            knobRow.addView(b, l);
        }
        if (kf == null) knobNote.setText("측정하면 저음·중음·고음 노브를 어디에 두면 좋을지 최종 설정값으로 알려드립니다.");
        else {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (hold) append(sb, "✓ 지금 설정 유지를 권장합니다. ", OK);
            sb.append(hold ? "바꿔도 예상 개선이 측정 오차 수준(0.5dB 미만)입니다. "
                    : "예상 효과: 목표와의 차이 ±" + f1(kf.rmsNow) + "dB → ±" + f1(kf.rmsAfter) + "dB. ");
            sb.append(learned() ? "학습한 내 차 노브 특성으로 계산했습니다." : "일반적인 노브 형태로 추정했습니다(1단계 ≈ 1dB). 2단계 노브 학습을 하면 정확해집니다.");
            if (kf.trebleFixed) sb.append("\n고음은 휴대폰 마이크로 믿기 어려워 추천하지 않습니다. 귀로 들어 거슬리면 1~2단계씩 내려 보세요.");
            sb.append("\n적용한 뒤 3단계에서 노브 값을 바꾸고 다시 측정하면 효과를 확인할 수 있습니다.");
            knobNote.setText(sb);
        }
        renderAdvice(r);
    }

    // ---------- 개선 방법 ----------

    static final class Advice {
        final String tier, title, why, effect;
        final int color;
        Advice(String tier, int color, String title, String why, String effect) {
            this.tier = tier; this.color = color; this.title = title; this.why = why; this.effect = effect;
        }
    }

    private List<Advice> computeAdvice(Dsp.Result r, Dsp.KnobFit kf, List<String> skip) {
        List<Advice> a = new ArrayList<>();
        if (r.snr < 20) {
            a.add(new Advice("먼저", ACC, "다시 측정하기", "측정 신뢰도가 " + Math.round(r.snr) + "dB로 낮아 결과를 믿기 어렵습니다.",
                    "볼륨을 올리거나 시동을 끄고 다시 측정한 뒤 개선 방법을 확인하세요."));
            return a;
        }
        if (kf != null) {
            if (kf.hold()) a.add(new Advice("무료", OK, "차량 EQ는 지금 설정 유지", "노브를 바꿔도 예상 개선이 측정 오차 수준입니다.", "EQ로 더 얻을 것이 거의 없습니다. 아래 방법을 살펴보세요."));
            else {
                String t = "차량 EQ: 저음 " + signed(kf.target[0]) + " · 중음 " + signed(kf.target[1]) + (kf.trebleFixed ? "" : " · 고음 " + signed(kf.target[2]));
                a.add(new Advice("무료", OK, t, "지금 노브(" + knobStr(cur) + ")에서 목표와의 차이가 ±" + f1(kf.rmsNow) + "dB입니다.",
                        "차이가 ±" + f1(kf.rmsAfter) + "dB로 줄어들 것으로 예상됩니다." + (learned() ? "" : " 노브 학습을 하면 더 정확해집니다.")));
            }
        }
        if (r.lr) {
            double ab = Math.abs(r.bal), ad = Math.abs(r.dly);
            if (ad > 1 || ab > 1.5) a.add(new Advice("무료", OK, "청취 위치를 운전석으로 맞추기",
                    (r.bal > 0 ? "왼쪽" : "오른쪽") + "이 " + f1(ab) + "dB 크고 " + f1(ad) + "ms 먼저 도착해, 소리가 한쪽으로 쏠립니다.",
                    "사운드 설정의 청취 위치를 운전석 중심으로 바꾸거나, 좌우 밸런스를 " + (r.bal > 0 ? "오른쪽" : "왼쪽") + "으로 1~2칸 옮겨 보세요."));
        } else {
            a.add(new Advice("무료", OK, "좌·우 분리 측정으로 쏠림 확인", "지금은 스테레오로 측정해 좌우 균형을 알 수 없습니다.",
                    "좌·우 분리 측정을 하면 소리가 한쪽으로 쏠리는지, 청취 위치 설정이 필요한지 알 수 있습니다."));
        }
        a.add(new Advice("무료", OK, "전후 밸런스를 뒤로 1~2칸 시험", "차량 선호도 연구에서 청취자들은 뒤쪽 스피커를 약 3dB 키운 쪽을 평균적으로 선호했습니다.",
                "소리가 앞에만 몰리지 않고 감싸는 느낌이 늘어납니다. 귀로 비교해 더 좋은 쪽을 고르세요."));
        a.add(new Advice("무료", OK, "음장 효과 켜고 끄며 비교", "차량의 음장 효과(예: Centerpoint 360)는 주파수 특성과 공간감을 크게 바꿉니다.",
                "켠 상태와 끈 상태로 각각 측정해, 점수가 높고 귀에 더 좋게 들리는 쪽을 고르세요."));
        if (r.thd >= 3) a.add(new Advice("무료", OK, "볼륨 한두 단계 낮추기", "지금 볼륨에서 찌그러짐이 " + f1(r.thd) + "%로 큽니다.",
                "찌그러짐이 줄어드는 볼륨이 이 시스템이 깨끗하게 낼 수 있는 최대 음량입니다."));
        int narrowLowDips = 0;
        boolean boxy = false, harshHi = false, dullHi = false;
        for (Dsp.Issue x : r.issues) {
            if (x.narrow && x.v < 0 && x.f >= 60 && x.f <= 500) narrowLowDips++;
            if (x.v > 0 && x.f >= 250 && x.f <= 1000) boxy = true;
            if (x.v > 0 && x.f >= 2000 && x.f < 10000) harshHi = true;
            if (x.v < 0 && !x.narrow && x.f >= 2000 && x.f < 10000) dullHi = true;
        }
        double after = kf == null ? r.rms : kf.rmsAfter;
        if (boxy || narrowLowDips > 0) a.add(new Advice("저비용", WARN, "도어 방음(댐핑) 시공",
                boxy ? "250Hz~1kHz 사이가 목표보다 튀어 소리가 울리는 듯 답답할 수 있습니다. 도어 철판이 함께 울리는 경우가 많습니다."
                        : "중저음 대역에 좁게 꺼진 골이 있습니다. 도어 안쪽 공간의 울림과 새는 소리가 원인일 수 있습니다.",
                "도어 울림이 줄어 중저음이 단단해지고 주행 소음도 줄어듭니다. 스피커를 바꾸기 전에 해두면 효과가 더 좋습니다."));
        boolean imaging = r.lr && (Math.abs(r.dly) > 1 || Math.abs(r.bal) > 2);
        if (narrowLowDips >= 2 || imaging || after > 3.5) {
            String why = "";
            if (narrowLowDips >= 2) why += "중저음에 좁은 골이 " + narrowLowDips + "곳 있습니다. 스피커끼리 소리가 상쇄되는 현상이라 저·중·고 노브로는 고칠 수 없습니다. ";
            if (imaging) why += "좌우 도달 시간 차 때문에 소리가 한쪽으로 쏠립니다. ";
            if (after > 3.5) why += "EQ를 조정해도 목표와의 차이가 ±" + f1(after) + "dB 남습니다.";
            a.add(new Advice("업그레이드", BLUE, "DSP 장착 + 측정 기반 튜닝", why.trim(),
                    "스피커마다 EQ와 도달 시간을 따로 맞춰 소리를 정면 중앙에 모으고 골을 줄일 수 있습니다. 장비보다 튜닝하는 사람의 실력이 결과를 좌우합니다."));
        }
        if (harshHi || dullHi) a.add(new Advice("업그레이드", BLUE, "앞 스피커 교체 (트위터 분리형)",
                harshHi ? "2~10kHz가 목표보다 튀어 고음이 날카롭게 들릴 수 있습니다." : "2~10kHz가 부족해 소리가 흐리고 답답할 수 있습니다.",
                "고음 해상도와 보컬 선명도가 좋아집니다. 교체 후에는 DSP나 EQ로 다시 맞춰야 효과가 제대로 납니다."));
        else skip.add("스피커 교체: 측정 가능한 범위의 고음이 목표에서 크게 벗어나지 않아 우선순위가 낮습니다.");
        boolean bassLover = r.pref != null && r.pref.bass >= 3;
        if (r.fb > 50 || (bassLover && r.bass < 70)) a.add(new Advice("업그레이드", BLUE, "서브우퍼 추가",
                "약 " + fmtF(r.fb) + " 아래부터 저음이 약해집니다" + (bassLover ? " (저음 많은 취향 기준)" : "") + ".",
                "더 낮고 깊은 저음이 나오고, 큰 볼륨에서도 저음에 여유가 생깁니다."));
        else skip.add("서브우퍼: 약 " + (r.fb <= 20 ? "20Hz" : fmtF(r.fb)) + "까지 저음이 나와 지금은 우선순위가 낮습니다.");
        if (r.thd >= 2) a.add(new Advice("업그레이드", BLUE, "외장 앰프",
                "평소 볼륨에서 찌그러짐이 " + f1(r.thd) + "%입니다. 순정 앰프의 출력이 부족할 수 있습니다.",
                "큰 볼륨에서도 소리가 찌그러지지 않고 여유가 생깁니다."));
        else skip.add("외장 앰프: 찌그러짐이 " + f1(r.thd) + "%로 낮아, 이 볼륨에서는 효과가 작습니다.");
        return a;
    }

    private void renderAdvice(Dsp.Result r) {
        adviceBox.removeAllViews();
        if (r == null) {
            adviceBox.addView(text("측정하면 아쉬운 부분을 채울 방법을 돈이 안 드는 것부터 순서대로 알려드립니다.", 14, MUT, false));
            return;
        }
        List<String> skip = new ArrayList<>();
        int n = 1;
        for (Advice a : computeAdvice(r, lastKnob, skip)) {
            LinearLayout box = vbox();
            box.setPadding(px(12), px(10), px(12), px(12));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(12));
            g.setColor(CARD);
            g.setStroke(px(1), LINE);
            box.setBackground(g);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(n++ + ". " + a.title, 15, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(pill(a.tier, a.color));
            box.addView(head);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, "측정 결과  ", INK);
            sb.append(a.why);
            TextView w = text("", 13, MUT, false);
            w.setText(sb);
            w.setLineSpacing(0, 1.25f);
            w.setPadding(0, px(6), 0, 0);
            box.addView(w);
            SpannableStringBuilder eb = new SpannableStringBuilder();
            append(eb, "기대 효과  ", INK);
            eb.append(a.effect);
            TextView e = text("", 13, MUT, false);
            e.setText(eb);
            e.setLineSpacing(0, 1.25f);
            e.setPadding(0, px(4), 0, 0);
            box.addView(e);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l.topMargin = px(8);
            adviceBox.addView(box, l);
        }
        if (!skip.isEmpty()) {
            TextView h = text("지금은 필요성이 낮은 것", 14, INK, true);
            h.setPadding(0, px(14), 0, px(2));
            adviceBox.addView(h);
            for (String s : skip) {
                TextView t = text("• " + s, 13, MUT, false);
                t.setLineSpacing(0, 1.25f);
                adviceBox.addView(t);
            }
        }
        TextView note = text("업그레이드는 하이브리드 차량과 순정 인포테인먼트 시공 경험이 있는 곳에서 하세요. 시공 전후로 같은 위치·같은 볼륨에서 측정해 기록끼리 비교하면 효과를 숫자로 확인할 수 있습니다.", 12, MUT, false);
        note.setLineSpacing(0, 1.25f);
        note.setPadding(0, px(12), 0, 0);
        adviceBox.addView(note);
    }

    // ================= 측정 기록 =================

    private void buildHistoryCard(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(text("측정 기록", 18, INK, true));
        c.addView(text("측정할 때마다 자동으로 저장됩니다. '비교'를 누르면 그 기록의 곡선이 결과 그래프에 회색 점선으로 겹쳐 보입니다.", 13, MUT, false));
        historyBox = vbox();
        c.addView(historyBox);
        Button ex = button("기록 전체 공유", false);
        ex.setTextSize(14);
        ex.setOnClickListener(v -> shareText("차량 사운드 측정 기록", buildHistoryReport()));
        c.addView(ex, lp(px(44), px(10)));
    }

    private JSONArray loadRecords() {
        try {
            return new JSONArray(store.getString("records", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveRecords(JSONArray a) {
        store.edit().putString("records", a.toString()).apply();
    }

    private void saveRecord() {
        Dsp.Result r = lastResult;
        if (r == null || sessionId == 0) return;
        try {
            JSONArray all = loadRecords();
            JSONObject o = null;
            int idx = -1;
            for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getLong("id") == sessionId) { o = all.getJSONObject(i); idx = i; }
            if (o == null) { o = new JSONObject(); o.put("id", sessionId); o.put("note", ""); }
            o.put("t", System.currentTimeMillis());
            o.put("mode", r.lr ? "좌·우 분리" : "스테레오");
            o.put("count", r.count);
            o.put("total", Math.round(r.total));
            o.put("tone", Math.round(r.tone));
            o.put("tilt", Math.round(r.tilt * 100) / 100.0);
            o.put("bass", Math.round(r.bass));
            o.put("clar", Math.round(r.clar));
            o.put("inBand", Math.round(r.inBand));
            o.put("fb", Math.round(r.fb));
            o.put("thd", Math.round(r.thd * 10) / 10.0);
            o.put("snr", Math.round(r.snr));
            o.put("pref", pref.name);
            o.put("knobs", knobStr(cur));
            o.put("source", AudioEngine.chosenSource >= 0 ? AudioEngine.sourceName(AudioEngine.chosenSource) : "자동");
            StringBuilder cv = new StringBuilder();
            for (int i = 0; i < Dsp.NF; i++) { if (i > 0) cv.append(','); cv.append(String.format(Locale.US, "%.1f", r.avg[i])); }
            o.put("curve", cv.toString());
            if (idx < 0) all.put(o);
            else all.put(idx, o);
            while (all.length() > 50) all.remove(0);
            saveRecords(all);
        } catch (Exception ignored) { }
        renderHistory();
    }

    private void renderHistory() {
        historyBox.removeAllViews();
        JSONArray all = loadRecords();
        if (all.length() == 0) {
            historyBox.addView(text("아직 저장된 기록이 없습니다.", 14, MUT, false));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("M/d HH:mm", Locale.KOREA);
        for (int i = all.length() - 1; i >= 0; i--) {
            try {
                JSONObject o = all.getJSONObject(i);
                final long id = o.getLong("id");
                boolean live = id == sessionId, cmp = id == compareId;
                LinearLayout box = vbox();
                box.setPadding(px(12), px(10), px(12), px(10));
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(px(12));
                g.setColor(cmp ? 0xFFF0F1F3 : CARD);
                g.setStroke(px(1), cmp ? 0xFF9AA0A8 : LINE);
                box.setBackground(g);
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                head.addView(text(fmt.format(new Date(o.getLong("t"))) + (live ? " · 진행 중" : ""), 14, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                int tot = o.getInt("total");
                head.addView(pill(tot + "점 " + gradeName(tot), tot >= 70 ? OK : tot >= 55 ? WARN : ACC));
                box.addView(head);
                box.addView(text(o.optString("pref") + " 기준 · 노브 " + o.optString("knobs") + " · " + o.optString("mode") + " " + o.optInt("count") + "곳 · 범위 안 " + o.optInt("inBand") + "%", 12, MUT, false));
                String note = o.optString("note");
                if (!note.isEmpty()) box.addView(text("메모: " + note, 13, INK, false));
                LinearLayout btns = new LinearLayout(this);
                btns.setOrientation(LinearLayout.HORIZONTAL);
                btns.setPadding(0, px(6), 0, 0);
                Button bc = button(cmp ? "비교 끄기" : "비교", false);
                Button bm = button("메모", false);
                Button bd = button("삭제", false);
                for (Button x : new Button[]{bc, bm, bd}) {
                    x.setTextSize(13);
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, px(38), 1);
                    l.rightMargin = px(6);
                    btns.addView(x, l);
                }
                bc.setEnabled(!live);
                bc.setAlpha(live ? 0.4f : 1f);
                bc.setOnClickListener(v -> { compareId = compareId == id ? -1 : id; updateCompare(); renderHistory(); });
                bm.setOnClickListener(v -> editNote(id, note));
                bd.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("이 기록을 지울까요? 되돌릴 수 없습니다.")
                        .setPositiveButton("삭제", (d, w) -> deleteRecord(id)).setNegativeButton("취소", null).show());
                box.addView(btns);
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                l.topMargin = px(8);
                historyBox.addView(box, l);
            } catch (Exception ignored) { }
        }
    }

    private JSONObject findRecord(long id) {
        JSONArray all = loadRecords();
        for (int i = 0; i < all.length(); i++) {
            try {
                if (all.getJSONObject(i).getLong("id") == id) return all.getJSONObject(i);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void editNote(long id, String old) {
        EditText et = new EditText(this);
        et.setText(old);
        et.setHint("예: 음장 효과 켬, 도어 방음 후");
        et.setPadding(px(16), px(12), px(16), px(12));
        new AlertDialog.Builder(this).setTitle("메모").setView(et)
                .setPositiveButton("저장", (d, w) -> {
                    try {
                        JSONArray all = loadRecords();
                        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getLong("id") == id) all.getJSONObject(i).put("note", et.getText().toString().trim());
                        saveRecords(all);
                    } catch (Exception ignored) { }
                    renderHistory();
                }).setNegativeButton("취소", null).show();
    }

    private void deleteRecord(long id) {
        JSONArray all = loadRecords(), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            try {
                if (all.getJSONObject(i).getLong("id") != id) out.put(all.getJSONObject(i));
            } catch (Exception ignored) { }
        }
        saveRecords(out);
        if (compareId == id) compareId = -1;
        if (sessionId == id) sessionId = 0;
        updateCompare();
        renderHistory();
    }

    private void updateCompare() {
        JSONObject o = compareId >= 0 ? findRecord(compareId) : null;
        if (o == null) {
            compareId = -1;
            chart.setCompare(null);
            compareNote.setText("");
            setLegend();
            return;
        }
        String[] f = o.optString("curve").split(",");
        if (f.length != Dsp.NF) { compareId = -1; chart.setCompare(null); compareNote.setText(""); return; }
        double[] c = new double[Dsp.NF];
        for (int i = 0; i < Dsp.NF; i++) c[i] = Double.parseDouble(f[i]);
        double sh = Dsp.alignShift(c, pref);
        for (int i = 0; i < Dsp.NF; i++) c[i] -= sh;
        chart.setCompare(c);
        SimpleDateFormat fmt = new SimpleDateFormat("M/d HH:mm", Locale.KOREA);
        String s = "비교 중: " + fmt.format(new Date(o.optLong("t"))) + " 기록 (" + o.optInt("total") + "점, 노브 " + o.optString("knobs") + ")";
        if (lastResult != null) {
            int d = (int) Math.round(lastResult.total) - o.optInt("total");
            s += " → 지금 " + Math.round(lastResult.total) + "점 (" + (d >= 0 ? "+" : "") + d + ")";
        }
        compareNote.setText(s);
        compareNote.setPadding(0, px(6), 0, 0);
        setLegend();
    }

    private String buildHistoryReport() {
        StringBuilder s = new StringBuilder("[차량 사운드 측정 기록]\n");
        JSONArray all = loadRecords();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA);
        for (int i = 0; i < all.length(); i++) {
            try {
                JSONObject o = all.getJSONObject(i);
                s.append(fmt.format(new Date(o.getLong("t")))).append(" · ").append(o.optInt("total")).append("점 · ")
                        .append(o.optString("pref")).append(" · 노브 ").append(o.optString("knobs")).append(" · ")
                        .append(o.optString("mode")).append(" ").append(o.optInt("count")).append("곳 · 범위 안 ").append(o.optInt("inBand"))
                        .append("% · 기울기 ").append(o.optDouble("tilt")).append(" · 저음 한계 ").append(o.optInt("fb")).append("Hz · 왜곡 ")
                        .append(o.optDouble("thd")).append("% · 신뢰도 ").append(o.optInt("snr")).append("dB");
                String note = o.optString("note");
                if (!note.isEmpty()) s.append(" · 메모: ").append(note);
                s.append("\n");
            } catch (Exception ignored) { }
        }
        // 최근 기록(최대 8개)의 주파수 응답: 지금 취향 목표에 300Hz~3kHz 평균을 맞춘 값
        List<double[]> curves = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = Math.max(0, all.length() - 8); i < all.length(); i++) {
            try {
                JSONObject o = all.getJSONObject(i);
                double[] c = uncsv(o.optString("curve"));
                double sh = Dsp.alignShift(c, pref);
                for (int j = 0; j < Dsp.NF; j++) c[j] -= sh;
                curves.add(c);
                names.add(new SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(new Date(o.getLong("t"))));
            } catch (Exception ignored) { }
        }
        if (!curves.isEmpty()) {
            s.append("\n[기록별 주파수 응답] 1/6옥타브 간격, dB (").append(pref.name).append(" 목표에 맞춤)\nHz, 목표");
            for (String n : names) s.append(", ").append(n);
            s.append("\n");
            for (int i = 0; i < Dsp.NF; i += 4) {
                s.append(Math.round(Dsp.FR[i])).append(String.format(Locale.US, ", %.1f", Dsp.target(Dsp.FR[i], pref)));
                for (double[] c : curves) s.append(String.format(Locale.US, ", %.1f", c[i]));
                s.append("\n");
            }
        }
        return s.toString();
    }

    // ================= 결과 공유 =================

    private void buildShareCard(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(text("결과 공유", 18, INK, true));
        c.addView(text("진단·노브 학습·점수·주파수별 측정값 전체를 글로 복사합니다. 채팅에 붙여넣어 분석을 요청할 수 있습니다.", 13, MUT, false));
        Button b = button("결과 복사·공유", false);
        b.setOnClickListener(v -> shareText("차량 사운드 측정 결과", buildReport()));
        c.addView(b, lp(px(48), px(10)));
        shareNote = text("", 13, OK, true);
        shareNote.setPadding(0, px(8), 0, 0);
        c.addView(shareNote);
    }

    private void shareText(String title, String t) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(title, t));
            shareNote.setText("✓ 복사했습니다. 채팅 입력창에 붙여넣거나, 공유할 앱을 고르세요.");
        } catch (RuntimeException e) {
            shareNote.setText("복사하지 못했습니다. 공유할 앱을 골라 주세요.");
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, title);
        i.putExtra(Intent.EXTRA_TEXT, t);
        try {
            startActivity(Intent.createChooser(i, title));
        } catch (RuntimeException ignored) { }
    }

    private String buildReport() {
        StringBuilder s = new StringBuilder();
        s.append("[차량 사운드 측정기 결과 v3]\n");
        s.append(deviceLine()).append("\n");
        s.append(sourceLine()).append("\n");
        if (!AudioEngine.lastInfo.isEmpty()) s.append("최근 녹음 · ").append(AudioEngine.lastInfo.replace("\n", " / ")).append("\n");
        s.append("신뢰 범위: ").append(lfUnreliable ? "80Hz" : "50Hz").append("~").append(hfUnreliable ? "5kHz (고음 신뢰 낮음)" : "10kHz")
                .append(chainComp ? " · 재생 경로 음량 자동 조절 감지" : "").append("\n");
        s.append("취향: ").append(pref.name).append(" (저음 ").append(signedD(pref.bass)).append(", 중저음 ").append(signedD(pref.lowMid))
                .append(", 보컬 ").append(signedD(pref.vocal)).append(", 고음 ").append(signedD(pref.treble)).append(")\n");
        s.append("지금 차량 EQ 노브: ").append(knobStr(cur)).append("\n");

        List<DiagItem> di = loadDiag();
        if (!di.isEmpty()) {
            s.append("\n[입력 진단] 방식: 자동음량 오차dB, 저음(중역 대비)dB, 고음(중역 대비)dB, 신뢰도dB\n");
            for (DiagItem it : di) {
                s.append(AudioEngine.sourceName(it.src)).append(it.src == AudioEngine.chosenSource ? " [사용 중]" : "").append(": ");
                if (it.d == null) s.append("사용 불가\n");
                else s.append(String.format(Locale.US, "%.1f, %+.1f, %+.1f, %.0f\n", it.d.linErr, it.d.lowRel, it.d.highRel, it.d.snr));
            }
        }
        if (learned()) {
            s.append("\n[노브 학습] 1단계당 최대 dB @ 주파수, 작용 범위, 판정\n");
            for (int k = 0; k < 3; k++) {
                Dsp.KnobInfo ki = Dsp.knobInfo(knobShape[k], k);
                s.append(Dsp.KNOBS[k]).append(String.format(Locale.US, ": %.2fdB @ %s, %s~%s, %s\n", ki.peak, fmtF(ki.peakF), fmtF(ki.lo), fmtF(ki.hi), ki.valid ? "정상" : "확인 필요(" + ki.problem + ")"));
            }
            s.append("학습 곡선(dB/단계) Hz, 저음, 중음, 고음\n");
            for (int i = 0; i < Dsp.NF; i += 8)
                s.append(Math.round(Dsp.FR[i])).append(String.format(Locale.US, ", %.2f, %.2f, %.2f\n", knobShape[0][i], knobShape[1][i], knobShape[2][i]));
        } else s.append("\n[노브 학습] 안 함 (일반형 추정 사용)\n");

        Dsp.Result r = lastResult;
        if (r == null) {
            s.append("\n측정 결과 없음\n");
            return s.toString();
        }
        int t = (int) Math.round(r.total);
        s.append("\n[측정] ").append(r.lr ? "좌·우 분리" : "스테레오").append(", 위치 ").append(r.count).append("곳 평균").append(r.converted ? " (노브 환산 포함)" : "").append("\n");
        s.append(String.format(Locale.US, "점수 %d (%s) · 음색 %d · 기울기 %d · 저음 %d · 깨끗함 %d", t, gradeName(t),
                Math.round(r.tone), Math.round(r.tiltScore), Math.round(r.bass), Math.round(r.clar)));
        if (r.lr) s.append(String.format(Locale.US, " · 밸런스 %d", Math.round(r.balScore)));
        s.append("\n");
        s.append(String.format(Locale.US, "범위(±%.1fdB) 안 %.0f%% · 초과량 %.2fdB · 기울기 %+.2fdB/옥타브 · 편차 ±%.1fdB · 저음 한계 %s · 왜곡 %.1f%% · 신뢰도 %.0fdB · 입력 최대 %.2f\n",
                TOL, r.inBand, r.excess, r.tilt, r.rms, r.fb <= 20 ? "20Hz 이하" : fmtF(r.fb), r.thd, r.snr, r.clip));
        s.append("기울기 원인: ").append(tiltCause(r).replace("\n", " ")).append("\n");
        if (r.lr) s.append(String.format(Locale.US, "좌우 음량 차 %+.1fdB(+는 왼쪽이 큼) · 도달 시간 차 %+.1fms(+는 왼쪽이 먼저)\n", r.bal, r.dly));
        if (lastKnob != null) {
            s.append("3밴드 권장 설정값(").append(learned() ? "학습값" : "일반형 추정").append("): 저음 ").append(signed(lastKnob.target[0]))
                    .append(", 중음 ").append(signed(lastKnob.target[1])).append(", 고음 ").append(lastKnob.trebleFixed ? "판단 불가" : signed(lastKnob.target[2]))
                    .append(String.format(Locale.US, " (지금 %s, 목표와의 차이 ±%.1f → ±%.1fdB%s)\n", knobStr(cur), lastKnob.rmsNow, lastKnob.rmsAfter, lastKnob.hold() ? ", 유지 권장" : ""));
        }
        s.append("9밴드 제안(지금 상태에서 바꿀 양): ");
        for (int i = 0; i < Dsp.EQ_BANDS.length; i++) {
            if (i > 0) s.append(", ");
            s.append(fmtF(Dsp.EQ_BANDS[i])).append(" ").append(signed(r.eqGain[i]));
        }
        s.append("\n");
        List<Advice> adv = computeAdvice(r, lastKnob, new ArrayList<>());
        if (!adv.isEmpty()) {
            s.append("개선 방법: ");
            for (int i = 0; i < adv.size(); i++) s.append(i > 0 ? " / " : "").append(adv.get(i).tier).append(") ").append(adv.get(i).title);
            s.append("\n");
        }
        boolean spread = r.count > 1;
        s.append("\n[주파수 응답] 1/6옥타브 간격, dB (지금 노브 기준, 300Hz~3kHz 평균을 목표에 맞춤)\nHz");
        for (Dsp.Channel c : r.ch) s.append(", ").append(c.name);
        s.append(", 목표");
        if (spread) s.append(", 위치간 편차폭");
        s.append("\n");
        for (int i = 0; i < Dsp.NF; i += 4) {
            double f = Dsp.FR[i];
            s.append(Math.round(f));
            for (Dsp.Channel c : r.ch) s.append(String.format(Locale.US, ", %.1f", c.n[i]));
            s.append(String.format(Locale.US, ", %.1f", Dsp.target(f, pref)));
            if (spread) s.append(String.format(Locale.US, ", %.1f", r.spread[i]));
            s.append("\n");
        }
        return s.toString();
    }

    // ================= 취향 =================

    static final String[] SLIDER_NAMES = {"저음", "중저음", "보컬", "고음"};
    static final String[] SLIDER_HINTS = {"쿵쿵 울리는 깊은 저음", "따뜻함·펀치감", "목소리의 또렷함", "밝기·반짝임"};

    private void buildTasteCard(LinearLayout root) {
        LinearLayout c = card(root);
        c.addView(text("내 취향 (목표 곡선)", 18, INK, true));
        c.addView(text("취향을 바꾸면 다시 측정하지 않아도 점수가 바로 다시 계산됩니다.", 13, MUT, false));
        TextView h1 = text("청취자 유형 · 하만 연구 기반", 13, INK, true);
        h1.setPadding(0, px(10), 0, 0);
        c.addView(h1);
        c.addView(chipRow(0, Dsp.RESEARCH_PRESETS));
        TextView h2 = text("장르 참고용 · 연구 근거 없음", 13, MUT, true);
        h2.setPadding(0, px(4), 0, 0);
        c.addView(h2);
        c.addView(chipRow(Dsp.RESEARCH_PRESETS, Dsp.PRESETS.length));
        for (int k = 0; k < 4; k++) {
            final int idx = k;
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, px(10), 0, 0);
            TextView val = text("0", 16, INK, true);
            val.setGravity(Gravity.END);
            head.addView(text(SLIDER_NAMES[k], 16, INK, true));
            head.addView(text("  " + SLIDER_HINTS[k], 13, MUT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
            sliders[k] = sb;
            sliderVals[k] = val;
        }
        prefHint = text("", 13, MUT, false);
        prefHint.setLineSpacing(0, 1.25f);
        prefHint.setPadding(0, px(10), 0, 0);
        c.addView(prefHint);
    }

    private View chipRow(int from, int to) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(6), 0, px(6));
        for (int i = from; i < to; i++) {
            final int idx = i;
            Button b = button(Dsp.PRESETS[i].name, false);
            b.setTextSize(15);
            b.setPadding(px(14), 0, px(14), 0);
            b.setOnClickListener(v -> choosePreset(idx));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44));
            l.rightMargin = px(8);
            row.addView(b, l);
            while (chips.size() <= i) chips.add(null);
            chips.set(i, b);
        }
        hs.addView(row);
        return hs;
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
        setLegend();
    }

    private void styleChips() {
        for (int i = 0; i < chips.size(); i++) {
            Button b = chips.get(i);
            if (b == null) continue;
            boolean on = i == presetIdx && pref.name.equals(Dsp.PRESETS[i].name);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(22));
            g.setColor(on ? INK : CARD);
            g.setStroke(px(1), on ? INK : LINE);
            b.setBackground(g);
            b.setTextColor(on ? 0xFFFFFFFF : INK);
        }
    }

    private void updateHint() {
        Dsp.Pref base = Dsp.PRESETS[presetIdx];
        String h = pref.name.equals("내 설정") ? "'" + base.name + "'에서 직접 조정한 값입니다. 그래프의 점선과 초록 띠가 내 목표입니다." : base.note;
        if (pref.name.equals("저음 강조") || pref.name.equals("락") || pref.name.equals("팝")) h += " 평소 크게 듣는다면 그 볼륨으로 측정하세요.";
        prefHint.setText(h);
    }

    private void loadPref() {
        presetIdx = Math.max(0, Math.min(Dsp.PRESETS.length - 1, store.getInt("preset2", 0)));
        Dsp.Pref base = Dsp.PRESETS[presetIdx];
        String name = store.getString("name2", base.name);
        pref = base.withValues(name, store.getFloat("b2", (float) base.bass), store.getFloat("lm2", (float) base.lowMid),
                store.getFloat("v2", (float) base.vocal), store.getFloat("t2", (float) base.treble));
    }

    private void savePref() {
        store.edit().putInt("preset2", presetIdx).putString("name2", pref.name)
                .putFloat("b2", (float) pref.bass).putFloat("lm2", (float) pref.lowMid)
                .putFloat("v2", (float) pref.vocal).putFloat("t2", (float) pref.treble).apply();
    }

    // ================= 공통 =================

    private void updateInfo(boolean afterRecording) {
        String un = AudioEngine.supportsUnprocessed(this) ? "예" : "기기 정보상 미지원";
        for (DiagItem it : loadDiag()) if (it.src == UNPROCESSED) un = it.valid() ? "예 (진단에서 정상 동작 확인)" : it.d == null ? "아니오 (진단에서 열리지 않음)" : un;
        String s = deviceLine() + "\n가공 없는 마이크 입력 지원: " + un + "\n" + sourceLine();
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

    static String knobStr(int[] k) {
        return "저" + signed(k[0]) + " 중" + signed(k[1]) + " 고" + signed(k[2]);
    }

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

    static String signedD(double v) { return (v > 0 ? "+" : "") + String.format(Locale.US, "%.1f", v); }

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

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

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

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(primary ? 17 : 15);
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
        LinearLayout c = vbox();
        int p = px(14);
        c.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(14));
        g.setColor(CARD);
        g.setStroke(px(1), LINE);
        c.setBackground(g);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = px(12);
        parent.addView(c, l);
        return c;
    }
}
