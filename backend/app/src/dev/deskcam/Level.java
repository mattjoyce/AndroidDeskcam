package dev.deskcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * A bubble level for the mount, drawn large enough to read at arm's length.
 *
 * The phone lives on a stand over a bench and the thing you are adjusting is a bracket,
 * not a screen. So this is deliberately coarse to look at and precise to land: a big
 * target, one bubble, and a range that closes in as you get nearer.
 *
 * Every judgement it makes is in {@link Levelling}, which has no Android in it and is
 * tested on the workstation. This class is the drawing and nothing else.
 *
 * <b>Which way the bubble goes.</b> It floats to the high side, like the bubble in a
 * spirit level, so you lower the edge it has drifted towards. That is one decision taken
 * once and applied to both axes: {@link Levelling#instruction} names the same edge in
 * words, in case the metaphor is not obvious to somebody who has only ever used a phone
 * level app, some of which do the opposite.
 */
public class Level extends View {

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float roll = 0f, pitch = 0f;
    private int range = 0;
    private boolean haveReading = false;

    private int colText, colDim, colLine, colOk, colAccent, colWarn, colCard;
    private boolean coloured = false;

    public Level(Context c) { super(c); }
    public Level(Context c, AttributeSet a) { super(c, a); }

    /**
     * The palette, read on the first draw rather than in the constructor.
     *
     * getContext() is overridable, so calling it while the object is still being built is
     * a 'this' escape, and this project compiles with -Xlint:all -Werror. Reading it here
     * also picks up the night palette correctly, since the first draw happens after the
     * view is attached and themed.
     */
    private void colours() {
        if (coloured) return;
        colText = getContext().getColor(R.color.text);
        colDim = getContext().getColor(R.color.dim);
        colLine = getContext().getColor(R.color.line);
        colOk = getContext().getColor(R.color.ok);
        colAccent = getContext().getColor(R.color.accent);
        colWarn = getContext().getColor(R.color.warn);
        colCard = getContext().getColor(R.color.card);
        coloured = true;
    }

    /** The current range's half-width in degrees, for whoever draws the words. */
    public float rangeDegrees() { return Levelling.span(range); }

    public boolean level() {
        return haveReading && Levelling.good(roll) && Levelling.good(pitch);
    }

    /**
     * A fresh reading, in degrees. Roll is the sideways lean and pitch the forward tip,
     * the same two the phone already reports on /api/orientation.
     */
    public void update(float rollDeg, float pitchDeg) {
        roll = rollDeg;
        pitch = pitchDeg;
        haveReading = true;
        range = Levelling.range(range, Math.max(Math.abs(roll), Math.abs(pitch)));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        colours();
        final float w = getWidth(), h = getHeight();
        final float cx = w / 2f, cy = h / 2f;
        final float r = Math.min(w, h) / 2f - dp(26);
        if (r <= 0) return;
        final float span = Levelling.span(range);

        ink.setStyle(Paint.Style.FILL);
        ink.setColor(colCard);
        canvas.drawCircle(cx, cy, r + dp(10), ink);

        // Rings at a quarter, a half and the full range, and the crosshair through them.
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(dp(1.5f));
        ink.setColor(colLine);
        for (float f : new float[]{0.25f, 0.5f, 1f}) canvas.drawCircle(cx, cy, r * f, ink);

        ink.setStrokeWidth(dp(1));
        canvas.drawLine(cx - r, cy, cx + r, cy, ink);
        canvas.drawLine(cx, cy - r, cx, cy + r, ink);

        // The target: inside this and the mount is level enough to stop. It fills in when
        // you land, because at arm's length a colour change carries further than a number.
        final float good = Math.max(r * (Levelling.GOOD_DEG / span), dp(7));
        if (level()) {
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(withAlpha(colOk, 40));
            canvas.drawCircle(cx, cy, good, ink);
        }
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(dp(2));
        ink.setColor(level() ? colOk : colDim);
        canvas.drawCircle(cx, cy, good, ink);

        // The range, named on the rim it belongs to, so the bubble's distance from the
        // middle can never lie about what it means.
        ink.setStyle(Paint.Style.FILL);
        ink.setTextAlign(Paint.Align.CENTER);
        ink.setTextSize(dp(13));
        ink.setColor(colDim);
        canvas.drawText("±" + trim(span) + "°", cx, cy - r - dp(9), ink);
        canvas.drawText("roll", cx, cy + r + dp(21), ink);

        canvas.save();
        canvas.rotate(-90f, cx - r - dp(14), cy);
        canvas.drawText("pitch", cx - r - dp(14), cy, ink);
        canvas.restore();

        if (!haveReading) return;

        // The bubble, on the high side. Clamped to the rim, because a bubble that has left
        // the card tells you nothing about which way to turn the bracket. The sign on the
        // vertical axis is the screen's, not the sensor's: a raised top edge is a positive
        // pitch and the bubble belongs up there, where the canvas counts down.
        final float bx = cx + Levelling.offset(roll, span) * r;
        final float by = cy - Levelling.offset(pitch, span) * r;
        ink.setColor(level() ? colOk
                : (Levelling.good(roll) || Levelling.good(pitch) ? colWarn : colAccent));
        canvas.drawCircle(bx, by, dp(14), ink);
        ink.setColor(colText);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(dp(1));
        canvas.drawCircle(bx, by, dp(14), ink);
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | (alpha << 24);
    }

    private static String trim(float v) {
        return v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
