package com.carsound.meter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** EQ 조정 제안: 0dB 기준선 위로 올리기(파랑), 아래로 내리기(코랄) 막대 */
public class EqView extends View {
    static final int UP = 0xFF2F66D0, DOWN = 0xFFFF5A4E, GRID = 0xFFE7E5E0, MUT = 0xFF5F646B, INK = 0xFF15171A, ZERO = 0xFFB9B7B1;
    static final String[] LABELS = {"63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
    private int[] gains;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            small = new Paint(Paint.ANTI_ALIAS_FLAG), value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final RectF r = new RectF();

    public EqView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStrokeWidth(1 * dp);
        small.setTextSize(11 * dp);
        small.setColor(MUT);
        value.setTextSize(13 * dp);
        value.setFakeBoldText(true);
        value.setTextAlign(Paint.Align.CENTER);
    }

    public void setGains(int[] g) { gains = g; invalidate(); }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (210 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        float left = 30 * dp, right = W - 4 * dp, top = 22 * dp, bottom = H - 30 * dp, mid = (top + bottom) / 2;
        float unit = (bottom - top) / 2 / 6f;

        small.setTextAlign(Paint.Align.RIGHT);
        for (int d = -6; d <= 6; d += 3) {
            float y = mid - d * unit;
            line.setColor(d == 0 ? ZERO : GRID);
            line.setStrokeWidth((d == 0 ? 1.5f : 1f) * dp);
            c.drawLine(left, y, right, y, line);
            c.drawText(d > 0 ? "+" + d : String.valueOf(d), left - 6 * dp, y + 4 * dp, small);
        }

        int n = LABELS.length;
        float slot = (right - left) / n;
        small.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            float cx = left + slot * (i + 0.5f);
            c.drawText(LABELS[i], cx, H - 10 * dp, small);
            if (gains == null) continue;
            int g = gains[i];
            float bw = Math.min(slot * 0.56f, 26 * dp);
            if (g == 0) {
                fill.setColor(ZERO);
                c.drawCircle(cx, mid, 3.5f * dp, fill);
                value.setColor(MUT);
                c.drawText("0", cx, mid - 9 * dp, value);
                continue;
            }
            float y = mid - g * unit;
            r.set(cx - bw / 2, Math.min(y, mid), cx + bw / 2, Math.max(y, mid));
            fill.setColor(g > 0 ? UP : DOWN);
            c.drawRoundRect(r, 5 * dp, 5 * dp, fill);
            value.setColor(g > 0 ? UP : DOWN);
            String t = g > 0 ? "+" + g : String.valueOf(g);
            if (g > 0) c.drawText(t, cx, y - 6 * dp, value);
            else c.drawText(t, cx, y + 16 * dp, value);
        }
        if (gains == null) {
            value.setColor(MUT);
            value.setFakeBoldText(false);
            c.drawText("측정하면 대역별 조정값이 표시됩니다", (left + right) / 2, mid - 10 * dp, value);
            value.setFakeBoldText(true);
        }
        small.setTextAlign(Paint.Align.LEFT);
        c.drawText("dB", 0, top - 8 * dp, small);
        c.drawText("Hz", 0, H - 10 * dp, small);
    }
}
