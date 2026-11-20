package com.carsound.meter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 주파수 응답 그래프: 목표 허용 범위(띠), 목표 곡선(점선), 측정 곡선, 비교 곡선, 신뢰 낮은 대역 */
public class FrView extends View {
    static final int[] COLORS = {0xFFFF5A4E, 0xFF2F66D0};
    private Dsp.Result result;
    private Dsp.Pref pref;
    private double tol = 2.5, hiLimit = 20000;
    private double[] compare;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            tgt = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            band = new Paint(Paint.ANTI_ALIAS_FLAG), cmp = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
    private final float dp;

    public FrView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        grid.setColor(0xFFDEDCD7);
        grid.setStrokeWidth(1 * dp);
        text.setColor(0xFF5F646B);
        text.setTextSize(11 * dp);
        tgt.setColor(0xFF8A8F96);
        tgt.setStyle(Paint.Style.STROKE);
        tgt.setStrokeWidth(1.5f * dp);
        tgt.setPathEffect(new DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        band.setColor(0x2E1D7A4A);
        band.setStyle(Paint.Style.FILL);
        cmp.setColor(0xFF9AA0A8);
        cmp.setStyle(Paint.Style.STROKE);
        cmp.setStrokeWidth(2f * dp);
        cmp.setPathEffect(new DashPathEffect(new float[]{3 * dp, 3 * dp}, 0));
        shade.setColor(0x1A15171A);
    }

    public void setResult(Dsp.Result r) { result = r; invalidate(); }

    public void setPref(Dsp.Pref p) { pref = p; invalidate(); }

    public void setBand(double tolerance, double hi) { tol = tolerance; hiLimit = hi; invalidate(); }

    /** 비교할 기록의 곡선(현재 목표 기준으로 이미 맞춘 값). null이면 비교 끄기 */
    public void setCompare(double[] curve) { compare = curve; invalidate(); }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (260 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        float pl = 34 * dp, pr = 8 * dp, pt = 8 * dp, pb = 22 * dp;
        double y0 = 20, y1 = -20;
        if (hiLimit < 20000) {
            float x = X(hiLimit, pl, W - pr);
            c.drawRect(x, pt, W - pr, H - pb, shade);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("신뢰 낮음", (x + W - pr) / 2, pt + 14 * dp, text);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        for (int d = -20; d <= 20; d += 10) {
            float y = Y(d, pt, H - pb, y0, y1);
            c.drawLine(pl, y, W - pr, y, grid);
            c.drawText((d > 0 ? "+" : "") + d, pl - 5 * dp, y + 4 * dp, text);
        }
        int[] fs = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
        String[] lb = {"20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k"};
        boolean narrow = W < 420 * dp;
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < fs.length; i++) {
            float x = X(fs[i], pl, W - pr);
            c.drawLine(x, pt, x, H - pb, grid);
            if (!narrow || i % 2 == 0 || i == fs.length - 1) c.drawText(lb[i], x, H - 6 * dp, text);
        }
        // 허용 범위 띠
        Path bp = new Path();
        for (int i = 0; i < Dsp.NF; i++) {
            float x = X(Dsp.FR[i], pl, W - pr), y = Y(Dsp.target(Dsp.FR[i], pref) + tol, pt, H - pb, y0, y1);
            if (i == 0) bp.moveTo(x, y); else bp.lineTo(x, y);
        }
        for (int i = Dsp.NF - 1; i >= 0; i--) bp.lineTo(X(Dsp.FR[i], pl, W - pr), Y(Dsp.target(Dsp.FR[i], pref) - tol, pt, H - pb, y0, y1));
        bp.close();
        c.drawPath(bp, band);
        Path p = new Path();
        for (int i = 0; i < Dsp.NF; i++) {
            float x = X(Dsp.FR[i], pl, W - pr), y = Y(Dsp.target(Dsp.FR[i], pref), pt, H - pb, y0, y1);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        c.drawPath(p, tgt);
        if (compare != null) c.drawPath(curve(compare, pl, W - pr, pt, H - pb, y0, y1), cmp);
        if (result == null) {
            c.drawText("측정하면 여기에 곡선이 그려집니다", (pl + W - pr) / 2, Y(-12, pt, H - pb, y0, y1), text);
            return;
        }
        for (int k = 0; k < result.ch.length; k++) {
            line.setColor(COLORS[k % COLORS.length]);
            c.drawPath(curve(Dsp.smooth(result.ch[k].n, 1 / 6.0), pl, W - pr, pt, H - pb, y0, y1), line);
        }
    }

    private Path curve(double[] n, float l, float r, float t, float b, double y0, double y1) {
        Path q = new Path();
        for (int i = 0; i < Dsp.NF; i++) {
            float x = X(Dsp.FR[i], l, r), y = Y(n[i], t, b, y0, y1);
            if (i == 0) q.moveTo(x, y); else q.lineTo(x, y);
        }
        return q;
    }

    private static float X(double f, float a, float b) {
        return (float) (a + Math.log(f / 20) / Math.log(1000) * (b - a));
    }

    private static float Y(double d, float top, float bottom, double y0, double y1) {
        double v = Math.max(y1, Math.min(y0, d));
        return (float) (top + (y0 - v) / (y0 - y1) * (bottom - top));
    }
}
