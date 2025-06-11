
package com.example.voiceanalyzerapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;



public class PlayerVisualizerView extends View {


    public static final int VISUALIZER_HEIGHT = 28; // You can adjust this

    private byte[] bytes;


    private float denseness;

    private Paint playedStatePainting = new Paint();

    private Paint notPlayedStatePainting = new Paint();

    private int width;
    private int height;

    public PlayerVisualizerView(Context context) {
        super(context);
        init();
    }

    public PlayerVisualizerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public byte[] getBytes() {
        return this.bytes;
    }


    private void init() {
        bytes = null;


        playedStatePainting.setStrokeWidth(1f);
        playedStatePainting.setAntiAlias(true);
        playedStatePainting.setColor(ContextCompat.getColor(getContext(), R.color.gray)); // Color for the part not yet played
        notPlayedStatePainting.setStrokeWidth(1f);
        notPlayedStatePainting.setAntiAlias(true);
        notPlayedStatePainting.setColor(ContextCompat.getColor(getContext(), R.color.colorAccent)); // Color for the played part
    }


    public void updateVisualizer(byte[] bytes) {
        this.bytes = bytes;
        invalidate(); // Request a redraw
    }


    public void updatePlayerPercent(float percent) {
        // Ensure percent is within bounds
        percent = Math.max(0f, Math.min(1f, percent));
        denseness = (int) Math.ceil(width * percent);
        invalidate(); // Request a redraw
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // If bytes are loaded, calculate desired width
        if (bytes != null) {
            float totalBarsCount = getMeasuredWidth() / dp(3f);
            if (totalBarsCount <= 0.1f) {
                totalBarsCount = (bytes.length * 8 / 5) / ( (bytes.length * 8 / 5f) / (getMeasuredWidth() / dp(3f)) );
                if (totalBarsCount <= 0.1f && bytes.length > 0) totalBarsCount = 100;
            }

            int samplesCount = (bytes.length * 8 / 5);
            float samplesPerBar = samplesCount / totalBarsCount;
            if (samplesPerBar == 0 && samplesCount > 0) samplesPerBar = 1;

            int calculatedWidth = 0;
            if (samplesPerBar > 0) {

                int numDrawableBars = (int) Math.ceil(samplesCount / samplesPerBar);
                calculatedWidth = numDrawableBars * dp(3f);
            } else if (bytes.length > 0) {

                calculatedWidth = bytes.length / 10 * dp(3f);
                if (calculatedWidth == 0 && bytes.length > 0) calculatedWidth = dp(100f); // Minimum width
            }


            if (calculatedWidth > 0) {
                setMeasuredDimension(calculatedWidth, getMeasuredHeight());
            } else {
                // If no bytes or calculation fails, use the default measured width or a minimal width
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
            }
        } else {
            // No bytes, use default measurement or a minimal placeholder size
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        }
    }


    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        width = getWidth();
        height = getHeight();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bytes == null || width == 0) {
            canvas.drawText("No audio data", getWidth()/2f - 50, getHeight()/2f, playedStatePainting); // Placeholder
            return;
        }

        float barAndSpaceWidth = dp(3f);
        float barWidth = dp(2f);

        int samplesCount = (bytes.length * 8 / 5);
        if (samplesCount == 0) return;

        float totalBarsCountBasedOnViewWidth = this.width / barAndSpaceWidth;

        if (totalBarsCountBasedOnViewWidth <= 0.1f) {
            if (bytes.length > 0) {
                totalBarsCountBasedOnViewWidth = Math.max(10f, bytes.length / 10f);
            } else {
                return;
            }
        }


        float samplesPerBar = samplesCount / totalBarsCountBasedOnViewWidth;
        if (samplesPerBar < 1 && samplesCount > 0) { // Ensure at least 1 sample contributes to a bar, avoid drawing too many bars
            samplesPerBar = 1;
            totalBarsCountBasedOnViewWidth = samplesCount; // Recalculate bars based on 1 sample per bar
        }
        if (totalBarsCountBasedOnViewWidth <= 0.1f) return;


        byte value;
        float barCounter = 0;
        int nextBarNum = 0;

        int y = (height - dp(VISUALIZER_HEIGHT)) / 2;
        int barNum = 0;
        int lastBarNum;
        int drawBarCount;

        // Loop through all potential bars we can draw for the *entire* audio data
        for (int a = 0; a < samplesCount; a++) {
            if (a < nextBarNum) { // Skip samples until we reach the start of the next bar segment
                continue;
            }
            // This loop structure is complex. It tries to group samples for each bar.
            // `a` is a sample index. `nextBarNum` seems to be the target sample index for the next bar.
            // The goal is to determine the amplitude for the bar at `x = barNum * barAndSpaceWidth`.

            drawBarCount = 0;
            lastBarNum = nextBarNum;
            while (lastBarNum == nextBarNum && barCounter <= samplesCount) { // Iterate while current sample `a` is part of the bar `barNum`
                barCounter += samplesPerBar;
                nextBarNum = (int) barCounter; // Determine the starting sample for the *next* bar
                drawBarCount++; // This seems to count how many times we advance `barNum` for the current sample `a`
            }

            int bitPointer = a * 5; // 5 bits per "sample" value in this visualization
            int byteNum = bitPointer / Byte.SIZE;
            int byteBitOffset = bitPointer % Byte.SIZE; // Corrected from bitPointer - byteNum * Byte.SIZE

            // Ensure we don't read past the end of the bytes array
            if (byteNum >= bytes.length) break;

            int currentByteCount = Byte.SIZE - byteBitOffset;
            int nextByteRest = 5 - currentByteCount;

            value = (byte) ((bytes[byteNum] >> byteBitOffset) & ((1 << Math.min(5, currentByteCount)) - 1)); // Corrected mask: (1 << N) - 1

            if (nextByteRest > 0) {
                if (byteNum + 1 < bytes.length) { // Check bounds for next byte
                    value <<= nextByteRest; // Shift current value to make space
                    value |= bytes[byteNum + 1] & ((1 << nextByteRest) - 1); // Mask for remaining bits from next byte
                } else {
                    // Not enough bits left in the array, pad with 0 or handle as incomplete
                    value <<= nextByteRest; // Shift anyway, lower bits will be 0
                }
            }


            for (int b = 0; b < drawBarCount; b++) {
                if (barNum >= totalBarsCountBasedOnViewWidth && samplesPerBar > 1) { // Don't draw more bars than intended if we scale to view width
                    // This condition might need refinement if we want the view to auto-size based on content
                    // For now, if samplesPerBar is 1, we draw one bar per sample, potentially making it very wide
                    // If we fix the width based on totalBarsCountBasedOnViewWidth, this break is correct.
                    // If width is wrap_content, we want to draw all bars.
                    // Let's assume the view width is pre-determined or we draw all possible bars
                    // if width is wrap_content. The onMeasure should handle setting the desired width.
                }

                float x = barNum * barAndSpaceWidth;
                float barHeightCalculated = Math.max(1, VISUALIZER_HEIGHT * value / 31.0f); // value is 0-31 (5 bits)
                float top = y + dp(VISUALIZER_HEIGHT - barHeightCalculated);
                float right = x + barWidth;
                float bottom = y + dp(VISUALIZER_HEIGHT);

                // Check if this bar is within the "played" part (denseness)
                // denseness is a pixel value representing the progress line
                if (x + barWidth < denseness) { // Entire bar is in the played part
                    canvas.drawRect(x, top, right, bottom, notPlayedStatePainting); // "notPlayedState" is actually "played" color
                } else if (x > denseness) { // Entire bar is in the not-played part
                    canvas.drawRect(x, top, right, bottom, playedStatePainting); // "playedState" is actually "not-played" color
                } else { // Bar is partially played
                    // Draw the played part
                    canvas.drawRect(x, top, denseness, bottom, notPlayedStatePainting);
                    // Draw the not-played part
                    canvas.drawRect(denseness, top, right, bottom, playedStatePainting);
                }
                barNum++;
                if (barNum * barAndSpaceWidth > getWidth() && getWidth() > 0) {
                    // If we are drawing beyond the visible width of the canvas,
                    // and the view is not meant to be wider (e.g. not wrap_content width)
                    // we could stop. But if it's in a ScrollView, we should continue.
                    // onMeasure should have set the correct total width for the view.
                }
            }
            if (a >= samplesCount -1) break; // safety break if a somehow exceeds samplesCount due to loop logic
        }
    }

    public int dp(float value) {
        if (value == 0) {
            return 0;
        }
        return (int) Math.ceil(getContext().getResources().getDisplayMetrics().density * value);
    }
}