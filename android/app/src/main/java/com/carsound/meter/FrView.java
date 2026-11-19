package com.carsound.meter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 주파수 응답 그래프: 목표 곡선(점선)과 측정 곡선 */
public class FrView extends View {
    static final int[] COLORS = {0xFFFF5A4E, 0xFF2F66D0};
    private Dsp.Result result;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            tgt = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        tgt.setStrokeWidth(2 * dp);
        tgt.setPathEffect(new DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setResult(Dsp.Result r) { result = r; invalidate(); }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, (int) (260 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        float pl = 34 * dp, pr = 8 * dp, pt = 8 * dp, pb = 22 * dp;
        double y0 = 15, y1 = -25;
        text.setTextAlign(Paint.Align.RIGHT);
        for (int d = -20; d <= 10; d += 10) {
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
        Path p = new Path();
        for (int i = 0; i < Dsp.NF; i++) {
            float x = X(Dsp.FR[i], pl, W - pr), y = Y(Dsp.target(Dsp.FR[i]), pt, H - pb, y0, y1);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        c.drawPath(p, tgt);
        if (result == null) {
            c.drawText("측정하면 여기에 곡선이 그려집니다", (pl + W - pr) / 2, Y(-14, pt, H - pb, y0, y1), text);
            return;
        }
        for (int k = 0; k < result.ch.length; k++) {
            Path q = new Path();
            double[] n = result.ch[k].n;
            for (int i = 0; i < Dsp.NF; i++) {
                float x = X(Dsp.FR[i], pl, W - pr), y = Y(n[i], pt, H - pb, y0, y1);
                if (i == 0) q.moveTo(x, y); else q.lineTo(x, y);
            }
            line.setColor(COLORS[k % COLORS.length]);
            c.drawPath(q, line);
        }
    }

    private static float X(double f, float a, float b) {
        return (float) (a + Math.log(f / 20) / Math.log(1000) * (b - a));
    }

    private static float Y(double d, float top, float bottom, double y0, double y1) {
        double v = Math.max(y1, Math.min(y0, d));
        return (float) (top + (y0 - v) / (y0 - y1) * (bottom - top));
    }
}
