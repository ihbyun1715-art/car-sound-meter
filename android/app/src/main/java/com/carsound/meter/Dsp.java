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

    /** 차량용 목표 곡선(하만 차량 타깃을 단순화한 근사), dB */
    public static double target(double f) {
        return 8 / (1 + (f / 90) * (f / 90)) - (f > 1000 ? 1.2 * log2(f / 1000) : 0);
    }

    /** 듣는 사람의 취향: 기본 목표 곡선에 더할 네 가지 조절값(dB)과 채점 가중치 */
    public static final class Pref {
        public final String name;
        public double bass, lowMid, vocal, treble;
        public final double wBass, wVocal, wClar;

        public Pref(String name, double bass, double lowMid, double vocal, double treble, double wBass, double wVocal, double wClar) {
            this.name = name; this.bass = bass; this.lowMid = lowMid; this.vocal = vocal; this.treble = treble;
            this.wBass = wBass; this.wVocal = wVocal; this.wClar = wClar;
        }

        public Pref withValues(String newName, double b, double lm, double v, double t) {
            return new Pref(newName, b, lm, v, t, wBass, wVocal, wClar);
        }
    }

    /** 프리셋: 이름, 저음, 중저음, 보컬, 고음, 저음 가중치, 보컬 가중치, 깨끗함 가중치 */
    public static final Pref[] PRESETS = {
            new Pref("기본", 0, 0, 0, 0, 1, 1, 1),
            new Pref("중저음 선호", 4, 2, 0, 0, 1.6, 1, 1),
            new Pref("또렷한 보컬", 0, -2, 2, 1, 1, 1.8, 1),
            new Pref("발라드", 1, 1, 1, -1, 1, 1.4, 1),
            new Pref("락", 3, 0, -1, 2, 1.3, 1, 1.6),
            new Pref("팝", 4, 0, 1, 1, 1.4, 1.2, 1),
    };

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
        public double[] n;
        Channel(String name, int p, int raw) { this.name = name; this.p = p; this.raw = raw; }
    }

    /** 한 번(한 위치)의 측정 결과 */
    public static final class Meas {
        public boolean lr;
        public int fs;
        public double clip;
        public Channel[] ch;
    }

    public static final class Issue {
        public final double f, v;
        Issue(double f, double v) { this.f = f; this.v = v; }
    }

    /** 여러 측정을 합친 최종 결과 */
    public static final class Result {
        public boolean lr;
        public int count;
        public Channel[] ch;
        public double clip, rms, fb, thd, snr, total, tone, bass, clar;
        public double bal = Double.NaN, dly = Double.NaN, balScore = Double.NaN;
        public double[] avg;
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
            c.n = normalize(spectrum(s, fs));
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

    /** 여러 위치에서 잰 측정을 평균 내고 점수를 매긴다 */
    public static Result summarize(List<Meas> ms) {
        return summarize(ms, null);
    }

    /** 측정은 그대로 두고 취향(p)에 맞춘 목표 곡선으로 채점한다 */
    public static Result summarize(List<Meas> ms, Pref p) {
        Meas first = ms.get(0);
        int M = ms.size(), nc = first.ch.length;
        Result res = new Result();
        res.lr = first.lr;
        res.count = M;
        res.ch = new Channel[nc];
        for (int c = 0; c < nc; c++) {
            Channel a = new Channel(first.ch[c].name, 0, 0);
            double[] pw = new double[NF];
            a.snr = Double.MAX_VALUE;
            for (Meas m : ms) {
                Channel x = m.ch[c];
                for (int i = 0; i < NF; i++) pw[i] += Math.pow(10, x.n[i] / 10);
                a.E += x.E / M;
                a.thd += x.thd / M;
                a.snr = Math.min(a.snr, x.snr);
            }
            a.n = new double[NF];
            for (int i = 0; i < NF; i++) a.n[i] = 10 * Math.log10(pw[i] / M);
            res.ch[c] = a;
        }
        for (Meas m : ms) res.clip = Math.max(res.clip, m.clip);

        res.avg = new double[NF];
        double[] dev = new double[NF];
        for (int i = 0; i < NF; i++) {
            double s = 0;
            for (Channel c : res.ch) s += c.n[i];
            res.avg[i] = s / nc;
        }
        // 취향 목표 곡선에 맞춰 300Hz~3kHz 평균 레벨을 다시 맞춘다
        double sh = 0; int shc = 0;
        for (int i = 0; i < NF; i++) if (FR[i] >= 300 && FR[i] <= 3000) { sh += res.avg[i] - target(FR[i], p); shc++; }
        sh /= shc;
        for (int i = 0; i < NF; i++) {
            res.avg[i] -= sh;
            for (Channel c : res.ch) c.n[i] -= sh;
            dev[i] = res.avg[i] - target(FR[i], p);
        }
        double s = 0, cnt = 0;
        for (int i = 0; i < NF; i++) if (FR[i] >= 50 && FR[i] <= 12000) { double w = weight(FR[i], p); s += w * dev[i] * dev[i]; cnt += w; }
        res.rms = Math.sqrt(s / cnt);
        res.fb = 20;
        for (int i = NF - 1; i >= 0; i--) {
            if (FR[i] > 100) continue;
            if (dev[i] < -6) { res.fb = FR[i]; break; }
        }
        res.thd = 0; res.snr = Double.MAX_VALUE;
        for (Channel c : res.ch) { res.thd = Math.max(res.thd, c.thd); res.snr = Math.min(res.snr, c.snr); }
        res.tone = clamp(100 - (res.rms - 2) * 12);
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
        if (res.lr) {
            res.total = (0.45 * res.tone + 0.25 * wb * res.bass + 0.15 * wc * res.clar + 0.15 * res.balScore) / (0.45 + 0.25 * wb + 0.15 * wc + 0.15);
        } else {
            res.total = (0.5 * res.tone + 0.3 * wb * res.bass + 0.2 * wc * res.clar) / (0.5 + 0.3 * wb + 0.2 * wc);
        }
        // 목표에서 크게 벗어난 구간
        double rf = 0, rv = 0; boolean run = false;
        for (int i = 0; i < NF; i++) {
            double f = FR[i], v = dev[i];
            boolean bad = f >= 40 && f <= 12000 && (v > 5 || v < -6);
            if (bad) {
                if (!run || Math.signum(v) != Math.signum(rv)) {
                    if (run) res.issues.add(new Issue(rf, rv));
                    run = true; rf = f; rv = v;
                } else if (Math.abs(v) > Math.abs(rv)) { rf = f; rv = v; }
            } else if (run) { res.issues.add(new Issue(rf, rv)); run = false; }
        }
        if (run) res.issues.add(new Issue(rf, rv));
        for (int b = 0; b < EQ_BANDS.length; b++) {
            double t = 0; int n = 0;
            for (int i = 0; i < NF; i++)
                if (FR[i] >= EQ_BANDS[b] / Math.sqrt(2) && FR[i] <= EQ_BANDS[b] * Math.sqrt(2)) { t += dev[i]; n++; }
            res.eqGain[b] = (int) Math.max(-6, Math.min(6, -Math.round(t / n)));
        }
        return res;
    }
}
