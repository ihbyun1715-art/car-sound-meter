package com.carsound.meter;

import java.util.ArrayList;
import java.util.List;

/** 스윕 측정 신호 생성, 디컨볼루션, 주파수 응답·왜곡·밸런스 분석. 안드로이드 의존성 없음. */
public final class Dsp {
    public static final double T = 8, F1 = 20, F2 = 20000, LEAD = 0.5, GAP = 2.5, TAIL = 2.0;
    public static final int NF = 241;
    public static final double[] FR = new double[NF];
    public static final int[] EQ_BANDS = {63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    static {
        for (int i = 0; i < NF; i++) FR[i] = 20 * Math.pow(1000, i / 240.0);
    }

    private Dsp() {}

    static double log2(double x) { return Math.log(x) / Math.log(2); }
    static double clamp(double v) { return Math.max(0, Math.min(100, v)); }

    /**
     * 기본 목표 곡선(dB). 하만의 청취실 선호 응답(완만하게 내려가는 고음)에서 출발해,
     * 차량 선호도 연구(Dennis, AES 2015: 평탄 대비 저음 +13dB, 고음 -2dB)를 근사했다.
     * 저음 쉘프의 정확한 꺾임 주파수는 공개 자료로 확인하지 못해 90Hz로 가정했다.
     */
    public static double target(double f) {
        return 12 / (1 + (f / 90) * (f / 90)) - (f > 1000 ? 0.6 * log2(f / 1000) : 0);
    }

    /** 듣는 사람의 취향: 기본 목표 곡선에 더할 네 가지 조절값(dB)과 채점 가중치 */
    public static final class Pref {
        public final String name, note;
        public final boolean research;
        public double bass, lowMid, vocal, treble;
        public final double wBass, wVocal, wClar;

        public Pref(String name, double bass, double lowMid, double vocal, double treble, double wBass, double wVocal, double wClar,
                    boolean research, String note) {
            this.name = name; this.bass = bass; this.lowMid = lowMid; this.vocal = vocal; this.treble = treble;
            this.wBass = wBass; this.wVocal = wVocal; this.wClar = wClar; this.research = research; this.note = note;
        }

        public Pref withValues(String newName, double b, double lm, double v, double t) {
            return new Pref(newName, b, lm, v, t, wBass, wVocal, wClar, research, note);
        }
    }

    /**
     * 프리셋. 앞의 셋은 하만 헤드폰 연구의 청취자 유형(64% / 15% / 21%)을 차량 목표에 옮긴 것이고,
     * 뒤의 넷은 연구 근거가 없는 장르 참고용이다.
     * 순서: 이름, 저음, 중저음, 보컬, 고음, 저음 가중치, 보컬 가중치, 깨끗함 가중치
     */
    public static final Pref[] PRESETS = {
            new Pref("표준", 0, 0, 0, 0, 1, 1, 1, true, "하만 연구에서 청취자의 64%가 선호한 균형입니다."),
            new Pref("저음 강조", 5, 0, 0, 0, 1.3, 1, 1, true, "청취자의 15%가 선호: 표준보다 저음 4~6dB 많게. 젊은 남성 비율이 높았습니다."),
            new Pref("저음 절제", -2, 0, 0, 0, 1, 1, 1, true, "청취자의 21%가 선호: 표준보다 저음 2dB 적게. 여성·고령 청취자 비율이 높았습니다."),
            new Pref("또렷한 보컬", 0, -2, 2, 1, 1, 1.8, 1, false, "장르 참고용(연구 근거 없음): 목소리 대역을 강조합니다."),
            new Pref("발라드", 1, 1, 1, -1, 1, 1.4, 1, false, "장르 참고용(연구 근거 없음): 따뜻하고 부드럽게."),
            new Pref("락", 3, 0, -1, 2, 1.3, 1, 1.6, false, "장르 참고용(연구 근거 없음): 펀치감 있는 V자형."),
            new Pref("팝", 4, 0, 1, 1, 1.4, 1.2, 1, false, "장르 참고용(연구 근거 없음): 저음과 선명함을 함께."),
    };
    public static final int RESEARCH_PRESETS = 3;

    static double bell(double f, double fc, double sigmaOct) {
        double x = log2(f / fc) / sigmaOct;
        return Math.exp(-0.5 * x * x);
    }

    /** 취향을 반영한 목표 곡선 */
    public static double target(double f, Pref p) {
        if (p == null) return target(f);
        return target(f)
                + p.bass / (1 + (f / 100) * (f / 100))
                + p.lowMid * bell(f, 180, 0.6)
                + p.vocal * bell(f, 2200, 0.7)
                + p.treble / (1 + (4000 / f) * (4000 / f));
    }

    /** 취향에 따라 대역별로 편차를 얼마나 엄격하게 볼지 */
    static double weight(double f, Pref p) {
        if (p == null) return 1;
        return 1 + (p.wBass - 1) / (1 + (f / 150) * (f / 150)) + (p.wVocal - 1) * bell(f, 2200, 0.8);
    }

    public static float[] makeSweep(int fs) {
        return makeSweep(fs, T);
    }

    public static float[] makeSweep(int fs, double T) {
        int n = (int) Math.round(T * fs), fd = (int) Math.round(0.05 * fs);
        double K = Math.log(F2 / F1);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double v = Math.sin(2 * Math.PI * F1 * T / K * (Math.exp(i / (double) fs * K / T) - 1));
            if (i < fd) v *= 0.5 - 0.5 * Math.cos(Math.PI * i / fd);
            if (i > n - fd) v *= 0.5 - 0.5 * Math.cos(Math.PI * (n - i) / fd);
            s[i] = (float) (v * 0.5);
        }
        return s;
    }

    /** 재생할 스테레오 신호 [왼쪽, 오른쪽] */
    public static float[][] stimulus(int fs, boolean lr) {
        float[] sw = makeSweep(fs);
        int ld = (int) Math.round(LEAD * fs), gp = (int) Math.round(GAP * fs), tl = (int) Math.round(TAIL * fs);
        int len = lr ? ld + 2 * sw.length + gp + tl : ld + sw.length + tl;
        float[] L = new float[len], R = new float[len];
        System.arraycopy(sw, 0, L, ld, sw.length);
        System.arraycopy(sw, 0, R, lr ? ld + sw.length + gp : ld, sw.length);
        return new float[][]{L, R};
    }

    // ---------- 입력 진단: 같은 스윕을 큰 소리와 12dB 작은 소리로 두 번 재생 ----------

    public static final double DT = 3, DGAP = 1.5, DQUIET = 0.25;

    public static float[][] diagStimulus(int fs) {
        float[] sw = makeSweep(fs, DT);
        int ld = (int) Math.round(LEAD * fs), gp = (int) Math.round(DGAP * fs), tl = (int) Math.round(1.5 * fs);
        int len = ld + 2 * sw.length + gp + tl;
        float[] L = new float[len], R = new float[len];
        for (int i = 0; i < sw.length; i++) {
            L[ld + i] = R[ld + i] = sw[i];
            L[ld + sw.length + gp + i] = R[ld + sw.length + gp + i] = (float) (sw[i] * DQUIET);
        }
        return new float[][]{L, R};
    }

    /** 한 입력 방식의 진단 결과 */
    public static final class Diag {
        /** 작은 소리 - 큰 소리 레벨 차(dB). 정상이라면 약 -12dB */
        public double levelDiff;
        /** 자동 음량 조절 흔적: |levelDiff - (-12)| */
        public double linErr;
        /** 40~80Hz 레벨 - 300Hz~3kHz 레벨(dB). 높을수록 저음을 덜 깎음 */
        public double lowRel;
        /** 8~12kHz 레벨 - 300Hz~3kHz 레벨(dB) */
        public double highRel;
        public double snr;
    }

    static double bandMean(double[] db, double lo, double hi) {
        double s = 0; int c = 0;
        for (int i = 0; i < NF; i++) if (FR[i] >= lo && FR[i] <= hi) { s += db[i]; c++; }
        return s / c;
    }

    public static Diag diagnose(float[] rec, int fs) {
        float[] sw = makeSweep(fs, DT);
        double[] h = deconv(rec, sw, fs);
        int N = h.length, WL = 1;
        while (WL < 0.34 * fs) WL <<= 1;
        int pk = 0; double pv = 0;
        for (int i = 0; i < N; i++) { double a = Math.abs(h[i]); if (a > pv) { pv = a; pk = i; } }
        int D = (int) Math.round((DT + DGAP) * fs), r = (int) Math.round(0.05 * fs);
        double[] a = localMax(h, pk + D, r), b = localMax(h, pk - D, r);
        int loud, quiet;
        if (a[0] > b[0]) { loud = pk; quiet = (int) a[1]; } else { loud = (int) b[1]; quiet = pk; }
        int pre = (int) Math.round(0.002 * fs);
        double[] sl = seg(h, loud - pre, WL), sq = seg(h, quiet - pre, WL);
        double[] dl = spectrum(sl, fs), dq = spectrum(sq, fs);
        Diag d = new Diag();
        d.levelDiff = bandMean(dq, 200, 4000) - bandMean(dl, 200, 4000);
        d.linErr = Math.abs(d.levelDiff - 20 * Math.log10(DQUIET));
        double mid = bandMean(dl, 300, 3000);
        d.lowRel = bandMean(dl, 40, 80) - mid;
        d.highRel = bandMean(dl, 8000, 12000) - mid;
        double nz = energy(seg(h, quiet + (int) Math.round(0.45 * fs), WL));
        d.snr = 10 * Math.log10(energy(sl) / Math.max(nz, 1e-30));
        return d;
    }

    public static void fft(double[] re, double[] im, boolean inv) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int b = n >> 1;
            for (; (j & b) != 0; b >>= 1) j ^= b;
            j ^= b;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double a = 2 * Math.PI / len * (inv ? 1 : -1), wr = Math.cos(a), wi = Math.sin(a);
            int h = len >> 1;
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < h; k++) {
                    int p = i + k, q = p + h;
                    double xr = re[q] * cr - im[q] * ci, xi = re[q] * ci + im[q] * cr;
                    re[q] = re[p] - xr; im[q] = im[p] - xi; re[p] += xr; im[p] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
        if (inv) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    static double taper(double f, int fs) {
        double hi = Math.min(22000, fs / 2.0);
        if (f <= 12 || f >= hi) return 0;
        if (f < 18) return 0.5 - 0.5 * Math.cos(Math.PI * (f - 12) / 6);
        if (f > 20000) return 0.5 + 0.5 * Math.cos(Math.PI * (f - 20000) / (hi - 20000));
        return 1;
    }

    static double[] deconv(float[] y, float[] x, int fs) {
        int N = 1;
        while (N < y.length + x.length) N <<= 1;
        double[] yr = new double[N], yi = new double[N], xr = new double[N], xi = new double[N];
        for (int i = 0; i < y.length; i++) yr[i] = y[i];
        for (int i = 0; i < x.length; i++) xr[i] = x[i];
        fft(yr, yi, false);
        fft(xr, xi, false);
        double mx = 0;
        for (int k = 0; k < N; k++) mx = Math.max(mx, xr[k] * xr[k] + xi[k] * xi[k]);
        double eps = mx * 1e-5, df = fs / (double) N;
        for (int k = 0; k < N; k++) {
            double w = taper((k <= N / 2 ? k : N - k) * df, fs);
            if (w == 0) { yr[k] = 0; yi[k] = 0; continue; }
            double p = xr[k] * xr[k] + xi[k] * xi[k] + eps;
            double r = (yr[k] * xr[k] + yi[k] * xi[k]) / p * w, m = (yi[k] * xr[k] - yr[k] * xi[k]) / p * w;
            yr[k] = r; yi[k] = m;
        }
        xr = null; xi = null;
        fft(yr, yi, true);
        return yr;
    }

    static double[] seg(double[] h, int start, int WL) {
        int N = h.length, fl = (int) Math.round(WL * 0.6);
        double[] o = new double[WL];
        for (int i = 0; i < WL; i++) {
            double w = i < fl ? 1 : 0.5 + 0.5 * Math.cos(Math.PI * (i - fl) / (WL - fl));
            o[i] = h[(((start + i) % N) + N) % N] * w;
        }
        return o;
    }

    static double energy(double[] s) {
        double e = 0;
        for (double v : s) e += v * v;
        return e;
    }

    static double[] spectrum(double[] s, int fs) {
        int WL = s.length;
        double[] re = s.clone(), im = new double[WL];
        fft(re, im, false);
        double df = fs / (double) WL;
        double[] out = new double[NF];
        for (int i = 0; i < NF; i++) {
            double f = FR[i];
            int lo = Math.max(1, (int) Math.floor(f * Math.pow(2, -1 / 12.0) / df));
            int hi = Math.max(lo, (int) Math.ceil(f * Math.pow(2, 1 / 12.0) / df));
            double t = 0; int c = 0;
            for (int k = lo; k <= hi && k < WL / 2; k++) { t += re[k] * re[k] + im[k] * im[k]; c++; }
            out[i] = 10 * Math.log10(t / Math.max(c, 1) + 1e-30);
        }
        return out;
    }

    static double[] normalize(double[] db) {
        double s = 0; int c = 0;
        for (int i = 0; i < NF; i++) if (FR[i] >= 300 && FR[i] <= 3000) { s += db[i] - target(FR[i]); c++; }
        double off = s / c;
        double[] o = new double[NF];
        for (int i = 0; i < NF; i++) o[i] = db[i] - off;
        return o;
    }

    public static final class Channel {
        public final String name;
        public int p, raw;
        public double E, snr, thd;
        /** 300Hz~3kHz를 목표 곡선에 맞춘 응답(dB). 결과에서는 '현재 노브 기준' 응답 */
        public double[] n;
        /** 맞추기 전 원래 레벨(dB). 노브 학습처럼 절대 변화량이 필요할 때 쓴다 */
        public double[] rawDb;
        Channel(String name, int p, int raw) { this.name = name; this.p = p; this.raw = raw; }
    }

    /** 한 번(한 위치)의 측정 결과 */
    public static final class Meas {
        public boolean lr;
        public int fs;
        public double clip;
        public Channel[] ch;
        /** 측정할 때의 차량 EQ 노브 값(저음, 중음, 고음) */
        public int[] knobs = new int[3];
    }

    /** 목표에서 크게 벗어난 구간 */
    public static final class Issue {
        public final double f, v, fLo, fHi;
        public final boolean narrow, posDep;
        Issue(double f, double v, double fLo, double fHi, boolean posDep) {
            this.f = f; this.v = v; this.fLo = fLo; this.fHi = fHi; this.posDep = posDep;
            this.narrow = log2(fHi / fLo) < 0.34;
        }
    }

    /** 채점 조건 */
    public static final class Opts {
        /** 허용 범위(±dB). 이 안의 차이는 감점하지 않는다 */
        public double tol = 2.5;
        /** 믿을 수 있는 측정 범위 */
        public double lo = 50, hi = 10000;
        /** 학습한 노브 모양(1단계당 dB). 없으면 null */
        public double[][] shapes;
        /** 지금 차량 EQ 노브 값 */
        public int[] cur = new int[3];
    }

    /** 여러 측정을 합친 최종 결과 */
    public static final class Result {
        public boolean lr, converted;
        public int count;
        public Channel[] ch;
        public double clip, rms, fb, thd, snr, total, tone, tiltScore, bass, clar;
        /** 허용 범위를 넘은 양(가중 RMS, dB), 범위 안 비율(%), 기울기(dB/옥타브, +면 고음 쪽이 큼) */
        public double excess, inBand, tilt;
        public double bal = Double.NaN, dly = Double.NaN, balScore = Double.NaN;
        /** 현재 노브 기준 평균 응답·목표와의 차이, 노브 0으로 환산한 차이 */
        public double[] avg, dev, devFlat, spread;
        public Pref pref;
        public Opts opts;
        public final List<Issue> issues = new ArrayList<>();
        public final int[] eqGain = new int[EQ_BANDS.length];
    }

    private static double[] localMax(double[] h, int c, int r) {
        int N = h.length;
        double m = 0; int ix = c;
        for (int i = -r; i <= r; i++) {
            int j = (((c + i) % N) + N) % N;
            double a = Math.abs(h[j]);
            if (a > m) { m = a; ix = j; }
        }
        return new double[]{m, ix};
    }

    public static Meas measure(float[] rec, int fs, boolean lr) {
        float[] sw = makeSweep(fs);
        double[] h = deconv(rec, sw, fs);
        int N = h.length, WL = 1;
        while (WL < 0.34 * fs) WL <<= 1;
        int pk = 0; double pv = 0;
        for (int i = 0; i < N; i++) { double a = Math.abs(h[i]); if (a > pv) { pv = a; pk = i; } }
        int pre = (int) Math.round(0.002 * fs);
        Meas m = new Meas();
        m.lr = lr; m.fs = fs;
        if (!lr) {
            m.ch = new Channel[]{new Channel("측정", pk, pk)};
        } else {
            int D = (int) Math.round((T + GAP) * fs), r = (int) Math.round(0.05 * fs);
            double[] a = localMax(h, pk + D, r), b = localMax(h, pk - D, r);
            int Lp, Rp;
            if (a[0] > b[0]) { Lp = pk; Rp = (int) a[1]; } else { Lp = (int) b[1]; Rp = pk; }
            m.ch = new Channel[]{new Channel("왼쪽", Lp, Lp), new Channel("오른쪽", Rp, Rp - D)};
        }
        int[] dts = new int[2];
        for (int k = 2; k <= 3; k++) dts[k - 2] = (int) Math.round(T * Math.log(k) / Math.log(F2 / F1) * fs);
        for (Channel c : m.ch) {
            double[] s = seg(h, c.p - pre, WL);
            c.E = energy(s);
            c.rawDb = spectrum(s, fs);
            c.n = normalize(c.rawDb);
            double nz = energy(seg(h, c.p + (int) Math.round(0.45 * fs), WL));
            c.snr = 10 * Math.log10(c.E / Math.max(nz, 1e-30));
            double he = 0;
            for (int d : dts) he += Math.max(0, energy(seg(h, c.p - d - pre, WL)) - nz);
            c.thd = 100 * Math.sqrt(he / c.E);
        }
        double clip = 0;
        for (float v : rec) clip = Math.max(clip, Math.abs(v));
        m.clip = clip;
        return m;
    }

    /** dB 곡선을 oct 옥타브 폭으로 평활 (FR 간격은 1/24옥타브) */
    public static double[] smooth(double[] db, double oct) {
        int h = (int) Math.round(oct * 12);
        double[] o = new double[NF];
        for (int i = 0; i < NF; i++) {
            double s = 0; int c = 0;
            for (int j = Math.max(0, i - h); j <= Math.min(NF - 1, i + h); j++) { s += db[j]; c++; }
            o[i] = s / c;
        }
        return o;
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        java.util.Collections.sort(s);
        int n = s.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static double knobEffect(double[][] shapes, int[] k, int i) {
        if (shapes == null || k == null) return 0;
        double s = 0;
        for (int j = 0; j < 3; j++) s += k[j] * shapes[j][i];
        return s;
    }

    /** 300Hz~3kHz 평균을 목표에 맞추는 이동량 */
    static double alignShift(double[] avg, Pref p) {
        double sh = 0; int c = 0;
        for (int i = 0; i < NF; i++) if (FR[i] >= 300 && FR[i] <= 3000) { sh += avg[i] - target(FR[i], p); c++; }
        return sh / c;
    }

    public static Result summarize(List<Meas> ms) {
        return summarize(ms, null, new Opts());
    }

    public static Result summarize(List<Meas> ms, Pref p) {
        return summarize(ms, p, new Opts());
    }

    /**
     * 측정은 그대로 두고 취향(p)과 채점 조건(o)으로 채점한다.
     * 노브 모양을 학습했다면 각 측정에서 그때의 노브 효과를 빼 '노브 0' 상태로 환산해 평균 내고,
     * 지금 노브 값의 효과를 더해 '현재 노브 기준' 결과를 만든다.
     */
    public static Result summarize(List<Meas> ms, Pref p, Opts o) {
        Meas first = ms.get(0);
        int M = ms.size(), nc = first.ch.length;
        Result res = new Result();
        res.lr = first.lr;
        res.count = M;
        res.pref = p;
        res.opts = o;
        res.ch = new Channel[nc];
        boolean conv = o.shapes != null;
        for (Meas m : ms) for (int k = 0; k < 3; k++) if (m.knobs[k] != o.cur[k]) res.converted = conv;
        double[][] flatCh = new double[nc][];
        for (int c = 0; c < nc; c++) {
            Channel a = new Channel(first.ch[c].name, 0, 0);
            double[] pw = new double[NF], pr = new double[NF];
            a.snr = Double.MAX_VALUE;
            for (Meas m : ms) {
                Channel x = m.ch[c];
                for (int i = 0; i < NF; i++) {
                    double ke = conv ? knobEffect(o.shapes, m.knobs, i) : 0;
                    pw[i] += Math.pow(10, (x.n[i] - ke) / 10);
                    pr[i] += Math.pow(10, (x.rawDb[i] - ke) / 10);
                }
                a.E += x.E / M;
                a.thd += x.thd / M;
                a.snr = Math.min(a.snr, x.snr);
            }
            a.n = new double[NF];
            a.rawDb = new double[NF];
            flatCh[c] = new double[NF];
            for (int i = 0; i < NF; i++) {
                flatCh[c][i] = 10 * Math.log10(pw[i] / M);
                double ke = conv ? knobEffect(o.shapes, o.cur, i) : 0;
                a.n[i] = flatCh[c][i] + ke;
                a.rawDb[i] = 10 * Math.log10(pr[i] / M) + ke;
            }
            res.ch[c] = a;
        }
        for (Meas m : ms) res.clip = Math.max(res.clip, m.clip);

        res.spread = new double[NF];
        if (M > 1) for (int i = 0; i < NF; i++) {
            double lo = 1e9, hi = -1e9;
            for (Meas m : ms) {
                double v = m.ch[0].n[i] - (conv ? knobEffect(o.shapes, m.knobs, i) : 0);
                lo = Math.min(lo, v); hi = Math.max(hi, v);
            }
            res.spread[i] = hi - lo;
        }

        res.avg = new double[NF];
        double[] avgFlat = new double[NF];
        for (int i = 0; i < NF; i++) {
            double s = 0, sf = 0;
            for (int c = 0; c < nc; c++) { s += res.ch[c].n[i]; sf += flatCh[c][i]; }
            res.avg[i] = s / nc;
            avgFlat[i] = sf / nc;
        }
        double sh = alignShift(res.avg, p), shf = alignShift(avgFlat, p);
        double[] dev = new double[NF];
        res.devFlat = new double[NF];
        for (int i = 0; i < NF; i++) {
            res.avg[i] -= sh;
            for (Channel c : res.ch) c.n[i] -= sh;
            dev[i] = res.avg[i] - target(FR[i], p);
            res.devFlat[i] = avgFlat[i] - shf - target(FR[i], p);
        }
        res.dev = dev;

        // 허용 범위 기반 채점: ±tol 안은 감점 없음, 봉우리는 골보다 무겁게, 1/6옥타브 평활
        double[] e = smooth(dev, 1 / 6.0);
        double s2 = 0, ws = 0, rs = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        int in = 0, tot = 0;
        for (int i = 0; i < NF; i++) {
            double f = FR[i];
            if (f < o.lo || f > o.hi) continue;
            double w = weight(f, p), ae = Math.abs(e[i]);
            double ex = Math.max(0, ae - o.tol) * (e[i] > 0 ? 1.0 : 0.6);
            s2 += w * ex * ex;
            rs += w * dev[i] * dev[i];
            ws += w;
            if (ae <= o.tol) in++;
            tot++;
            double x = log2(f / 1000);
            sx += w * x; sy += w * e[i]; sxx += w * x * x; sxy += w * x * e[i];
        }
        res.excess = Math.sqrt(s2 / ws);
        res.rms = Math.sqrt(rs / ws);
        res.inBand = 100.0 * in / Math.max(tot, 1);
        res.tilt = (ws * sxy - sx * sy) / (ws * sxx - sx * sx);
        res.tone = clamp(100 - 20 * res.excess);
        res.tiltScore = clamp(100 - 40 * Math.max(0, Math.abs(res.tilt) - 0.3));

        // 저음 한계: 1/3옥타브 평활 곡선이 목표보다 6dB 넘게 낮은 상태가 그 아래로 이어지는 지점
        double[] devS = smooth(dev, 1 / 3.0);
        res.fb = 20;
        for (int i = NF - 1; i >= 0; i--) {
            if (FR[i] > 100) continue;
            if (devS[i] >= -6) continue;
            boolean sustained = true;
            for (int j = i; j >= Math.max(0, i - 8); j--) if (devS[j] >= -6) { sustained = false; break; }
            if (sustained) { res.fb = FR[i]; break; }
        }

        res.thd = 0; res.snr = Double.MAX_VALUE;
        for (Channel c : res.ch) { res.thd = Math.max(res.thd, c.thd); res.snr = Math.min(res.snr, c.snr); }
        res.bass = clamp(100 - log2(Math.max(res.fb, 35) / 35) * 55);
        res.clar = clamp(100 - (res.thd - 1) * 12);
        if (res.lr) {
            Channel L = res.ch[0], R = res.ch[1];
            double d = 0; int k = 0;
            for (int i = 0; i < NF; i++) if (FR[i] >= 200 && FR[i] <= 4000) { d += L.n[i] - R.n[i] + 10 * Math.log10(L.E / R.E); k++; }
            res.bal = d / k;
            double dl = 0;
            for (Meas m : ms) dl += (m.ch[1].raw - m.ch[0].raw) / (double) m.fs * 1000 / M;
            res.dly = dl;
            res.balScore = clamp(100 - (Math.abs(res.bal) - 1) * 15);
        }
        double wb = p == null ? 1 : p.wBass, wc = p == null ? 1 : p.wClar;
        double num = 0.35 * res.tone + 0.2 * res.tiltScore + 0.25 * wb * res.bass + 0.2 * wc * res.clar;
        double den = 0.35 + 0.2 + 0.25 * wb + 0.2 * wc;
        if (res.lr) { num += 0.15 * res.balScore; den += 0.15; }
        res.total = num / den;

        // 목표에서 크게 벗어난 구간(믿을 수 있는 범위 안에서만)
        int start = -1; double rf = 0, rv = 0;
        double fmax = Math.min(12000, o.hi);
        for (int i = 0; i <= NF; i++) {
            double f = i < NF ? FR[i] : 0, v = i < NF ? dev[i] : 0;
            boolean bad = i < NF && f >= 40 && f <= fmax && (v > 5 || v < -6);
            boolean same = bad && start >= 0 && Math.signum(v) == Math.signum(rv);
            if (start >= 0 && !same) {
                double sp = 0;
                for (int j = start; j < i; j++) sp += res.spread[j];
                sp /= (i - start);
                res.issues.add(new Issue(rf, rv, FR[start], FR[i - 1], M > 1 && sp > 4));
                start = -1;
            }
            if (bad && start < 0) { start = i; rf = f; rv = v; }
            else if (bad && Math.abs(v) > Math.abs(rv)) { rf = f; rv = v; }
        }

        // 9밴드 제안(현재 상태에서 바꿀 양): 대역 중앙값, 올리기는 +3dB까지, 믿을 수 없는 대역은 0
        for (int b = 0; b < EQ_BANDS.length; b++) {
            if (EQ_BANDS[b] > o.hi) { res.eqGain[b] = 0; continue; }
            List<Double> v = new ArrayList<>();
            for (int i = 0; i < NF; i++)
                if (FR[i] >= EQ_BANDS[b] / Math.sqrt(2) && FR[i] <= EQ_BANDS[b] * Math.sqrt(2)) v.add(dev[i]);
            res.eqGain[b] = (int) Math.max(-6, Math.min(3, -Math.round(median(v))));
        }
        return res;
    }

    // ---------- 3밴드(저음·중음·고음) 노브 추천 ----------

    public static final String[] KNOBS = {"저음", "중음", "고음"};

    /** 학습 전 쓰는 일반적인 톤 컨트롤 모양(1단계당 dB) */
    public static double[] genericKnob(int k) {
        double[] s = new double[NF];
        for (int i = 0; i < NF; i++) {
            double f = FR[i];
            if (k == 0) s[i] = 1 / (1 + (f / 120) * (f / 120));
            else if (k == 1) s[i] = bell(f, 800, 1.0);
            else s[i] = 1 / (1 + (6000 / f) * (6000 / f));
        }
        return s;
    }

    /** 노브 추천 결과. target은 권장 '설정값'(현재 값이 아니라 최종 위치) */
    public static final class KnobFit {
        public final int[] target = new int[3];
        public final int[] cur = new int[3];
        public double rmsNow, rmsAfter;
        public boolean learned, trebleFixed;

        /** 바꿔도 나아질 게 거의 없으면 true */
        public boolean hold() {
            boolean small = true;
            for (int k = 0; k < 3; k++) if (Math.abs(target[k] - cur[k]) > 1) small = false;
            return small || rmsNow - rmsAfter < 0.5;
        }
    }

    static double alignedRms(double[] d, double[] w, Pref p, double lo, double hi) {
        double m = 0, sw = 0;
        for (int i = 0; i < NF; i++) if (w[i] > 0) { m += w[i] * d[i]; sw += w[i]; }
        m /= sw;
        double s = 0, c = 0;
        for (int i = 0; i < NF; i++) {
            double f = FR[i];
            if (f < lo || f > hi) continue;
            double ww = weight(f, p);
            s += ww * (d[i] - m) * (d[i] - m);
            c += ww;
        }
        return Math.sqrt(s / c);
    }

    /**
     * 목표와의 차이가 가장 작아지는 노브 설정값을 최소자승으로 구한다.
     * 학습한 모양이 있으면 '노브 0' 환산 응답에서 절대 설정값을, 없으면 현재 상태에서의 변화량을 구해
     * 현재 값에 더한다. 믿을 수 없는 대역과 위치 편차가 큰 대역은 덜 반영한다.
     */
    public static KnobFit fitKnobs(Result r, double[][] shapes, boolean learned, int maxStep) {
        Opts o = r.opts;
        int K = 3, V = K + 1;
        // 고음을 믿을 수 없으면(측정 상한이 8kHz 미만) 고음 노브는 지금 값에 고정하고 저음·중음만 맞춘다
        boolean fixTreble = o.hi < 8000;
        double[] base = smooth(learned ? r.devFlat : r.dev, 1 / 3.0);
        if (fixTreble && learned) for (int i = 0; i < NF; i++) base[i] += o.cur[2] * shapes[2][i];
        double[][] A = new double[V][V];
        double[] B = new double[V];
        double[] w = new double[NF];
        for (int i = 0; i < NF; i++) {
            double f = FR[i];
            if (f < Math.max(40, o.lo - 10) || f > Math.min(12000, o.hi)) continue;
            w[i] = weight(f, r.pref) / (1 + r.spread[i] / 3);
            double[] x = {shapes[0][i], shapes[1][i], fixTreble ? 0 : shapes[2][i], 1};
            for (int a = 0; a < V; a++) {
                B[a] += w[i] * x[a] * (-base[i]);
                for (int b = 0; b < V; b++) A[a][b] += w[i] * x[a] * x[b];
            }
        }
        for (int a = 0; a < V; a++) A[a][a] += 1e-6 + (a < K ? 0.02 * A[a][a] : 0);
        double[] g = solve(A, B);
        KnobFit kf = new KnobFit();
        kf.learned = learned;
        kf.trebleFixed = fixTreble;
        for (int k = 0; k < K; k++) {
            kf.cur[k] = o.cur[k];
            double t = (k == 2 && fixTreble) ? o.cur[k] : learned ? g[k] : o.cur[k] + g[k];
            kf.target[k] = (int) Math.max(-maxStep, Math.min(maxStep, Math.round(t)));
        }
        double[] now = new double[NF], after = new double[NF];
        for (int i = 0; i < NF; i++) {
            now[i] = r.dev[i];
            if (learned) after[i] = r.devFlat[i] + knobEffect(shapes, kf.target, i);
            else {
                after[i] = r.dev[i];
                for (int k = 0; k < K; k++) after[i] += (kf.target[k] - kf.cur[k]) * shapes[k][i];
            }
        }
        double hi = Math.min(12000, o.hi);
        kf.rmsNow = alignedRms(now, w, r.pref, o.lo, hi);
        kf.rmsAfter = alignedRms(after, w, r.pref, o.lo, hi);
        return kf;
    }

    static double[] solve(double[][] A0, double[] b0) {
        int n = b0.length;
        double[][] A = new double[n][];
        for (int i = 0; i < n; i++) A[i] = A0[i].clone();
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
            double[] t = A[c]; A[c] = A[piv]; A[piv] = t;
            double tb = b[c]; b[c] = b[piv]; b[piv] = tb;
            if (Math.abs(A[c][c]) < 1e-12) continue;
            for (int r = c + 1; r < n; r++) {
                double f = A[r][c] / A[c][c];
                for (int k = c; k < n; k++) A[r][k] -= f * A[c][k];
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) s -= A[r][k] * x[k];
            x[r] = Math.abs(A[r][r]) < 1e-12 ? 0 : s / A[r][r];
        }
        return x;
    }

    /** 노브 학습: 기준 측정과 노브를 steps 단계 올린 측정의 차이로 1단계당 모양을 구한다 */
    public static double[] learnKnob(double[] baseRaw, double[] knobRaw, int steps) {
        double[] d = new double[NF];
        for (int i = 0; i < NF; i++) d[i] = (knobRaw[i] - baseRaw[i]) / steps;
        return smooth(d, 1 / 3.0);
    }

    /** 학습한 노브 모양 요약과 유효성 */
    public static final class KnobInfo {
        public double peak, peakF, lo, hi, leak;
        public boolean valid;
        public String problem = "";
    }

    static double meanAbs(double[] s, double lo, double hi) {
        double t = 0; int c = 0;
        for (int i = 0; i < NF; i++) if (FR[i] >= lo && FR[i] <= hi) { t += Math.abs(s[i]); c++; }
        return c == 0 ? 0 : t / c;
    }

    public static KnobInfo knobInfo(double[] s, int k) {
        KnobInfo ki = new KnobInfo();
        int pi = 0;
        for (int i = 0; i < NF; i++) if (FR[i] >= 30 && FR[i] <= 16000 && s[i] > s[pi]) pi = i;
        ki.peak = s[pi];
        ki.peakF = FR[pi];
        int a = pi, b = pi;
        while (a > 0 && s[a - 1] >= ki.peak / 2) a--;
        while (b < NF - 1 && s[b + 1] >= ki.peak / 2) b++;
        ki.lo = FR[a];
        ki.hi = FR[b];
        double pk = Math.max(Math.abs(ki.peak), 1e-6);
        if (k == 0) ki.leak = meanAbs(s, 1000, 5000) / pk;
        else if (k == 1) ki.leak = (meanAbs(s, 40, 60) + meanAbs(s, 8000, 10000)) / 2 / pk;
        else ki.leak = meanAbs(s, 60, 300) / pk;
        boolean place = k == 0 ? ki.peakF < 400 : k == 1 ? (ki.peakF >= 150 && ki.peakF <= 6000) : ki.peakF >= 1500;
        if (ki.peak < 0.3) ki.problem = "노브 효과가 거의 측정되지 않았습니다. 노브를 +5로 바꾸지 않았거나 볼륨이 바뀌었을 수 있습니다.";
        else if (!place) ki.problem = "예상과 다른 대역이 움직였습니다. 다른 노브를 바꿨거나 휴대폰 위치가 바뀌었을 수 있습니다.";
        else if (ki.leak > 0.35) ki.problem = "담당하지 않는 대역까지 함께 바뀌었습니다. 학습 중 휴대폰 위치나 볼륨이 바뀌었을 수 있습니다.";
        ki.valid = ki.problem.isEmpty();
        return ki;
    }
}
