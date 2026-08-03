package com.shangzhili.electricityreminder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;

/** 随主题着色的云朵对话气泡，底部小尾巴朝向电小侠角色。 */
public final class CloudSpeechTextView extends AppCompatTextView {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cloud = new Path();

    public CloudSpeechTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(context.getColor(R.color.speech_surface));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(getResources().getDisplayMetrics().density);
        stroke.setColor(context.getColor(R.color.border));
        setTextColor(context.getColor(R.color.text_primary));
        setGravity(android.view.Gravity.CENTER);
        setPadding(dp(18), dp(15), dp(18), dp(23));
        setBackground(null);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float bottom = h - dp(12);
        cloud.reset();
        cloud.moveTo(dp(14), bottom);
        cloud.cubicTo(dp(3), bottom, dp(2), h * .64f, dp(10), h * .54f);
        cloud.cubicTo(dp(2), h * .36f, dp(15), h * .19f, dp(31), h * .25f);
        cloud.cubicTo(dp(38), dp(2), w * .38f, dp(1), w * .45f, h * .20f);
        cloud.cubicTo(w * .57f, dp(2), w * .72f, dp(5), w * .75f, h * .23f);
        cloud.cubicTo(w - dp(8), h * .18f, w + dp(1), h * .43f, w - dp(8), h * .55f);
        cloud.cubicTo(w, h * .72f, w - dp(10), bottom, w - dp(24), bottom);
        cloud.lineTo(w * .42f, bottom);
        cloud.lineTo(w * .31f, h);
        cloud.lineTo(w * .29f, bottom);
        cloud.close();
        canvas.drawPath(cloud, fill);
        canvas.drawPath(cloud, stroke);
        super.onDraw(canvas);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
