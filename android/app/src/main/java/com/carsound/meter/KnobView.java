package com.carsound.meter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 노브 학습 결과: 노브 1단계를 올렸을 때 주파수별로 몇 dB 바뀌는지 */
public class KnobView extends View {
    static final int[] COLORS = {0xFF2F66D0, 0xFF1D7A4A, 0xFFFF5A4E};
    private double[][] shapes;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    public KnobView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        grid.setColor(0xFFDEDCD7);
        grid.setStrokeWidth(1 * dp);
        text.setColor(0xFF5F646B);
        text.setTextSize(11 * dp);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setShapes(double[][] s) { shapes = s; invalidate(); }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (180 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        float pl = 34 * dp, pr = 8 * dp, pt = 8 * dp, pb = 22 * dp;
        double mx = 1;
        if (shapes != null) for (double[] s : shapes) if (s != null) for (double v : s) mx = Math.max(mx, Math.abs(v));
        double y0 = Math.ceil(mx), y1 = -Math.max(1, Math.ceil(mx / 2));
        text.setTextAlign(Paint.Align.RIGHT);
        for (int d = (int) y1; d <= y0; d++) {
            float y = Y(d, pt, H - pb, y0, y1);
            grid.setStrokeWidth((d == 0 ? 1.5f : 1f) * dp);
            c.drawLine(pl, y, W - pr, y, grid);
            c.drawText((d > 0 ? "+" : "") + d, pl - 5 * dp, y + 4 * dp, text);
        }
        int[] fs = {20, 100, 1000, 10000};
        String[] lb = {"20", "100", "1k", "10k"};
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < fs.length; i++) {
            float x = X(fs[i], pl, W - pr);
            c.drawLine(x, pt, x, H - pb, grid);
            c.drawText(lb[i], x, H - 6 * dp, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("dB/단계", pl + 4 * dp, pt + 12 * dp, text);
        if (shapes == null) return;
        for (int k = 0; k < shapes.length; k++) {
            if (shapes[k] == null) continue;
            Path q = new Path();
            for (int i = 0; i < Dsp.NF; i++) {
                float x = X(Dsp.FR[i], pl, W - pr), y = Y(shapes[k][i], pt, H - pb, y0, y1);
                if (i == 0) q.moveTo(x, y); else q.lineTo(x, y);
            }
            line.setColor(COLORS[k]);
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
