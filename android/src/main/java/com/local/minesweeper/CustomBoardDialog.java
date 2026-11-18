package com.local.minesweeper;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.OverScroller;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static com.local.minesweeper.GameDialog.MEDIUM;
import static com.local.minesweeper.GameDialog.dp;
import static com.local.minesweeper.GameDialog.text;
import static com.local.minesweeper.GameView.*;

final class CustomBoardDialog {
    interface OnApply {
        void apply(int columns, int rows, int rate);
    }

    static GameDialog show(Activity activity, int columns, int rows, int rate, boolean noGuess, OnApply onApply) {
        GameDialog dialog = new GameDialog(activity, "自定义棋盘");
        LinearLayout columnsRow = new LinearLayout(activity);
        columnsRow.setOrientation(LinearLayout.HORIZONTAL);
        columnsRow.setBaselineAligned(false);
        dialog.addContent(columnsRow, 16);
        Wheel widthPicker = addWheel(columnsRow, "列数", Minefield.MIN_CUSTOM_SIZE, Minefield.MAX_CUSTOM_SIZE, columns, "");
        Wheel heightPicker = addWheel(columnsRow, "行数", Minefield.MIN_CUSTOM_SIZE, Minefield.MAX_CUSTOM_SIZE, rows, "");
        Wheel ratePicker = addWheel(columnsRow, "雷率", 1, 99, rate, "%");

        TextView preview = text(activity, "", 15, CREAM, true);
        preview.setGravity(Gravity.CENTER);
        dialog.addContent(preview, 12);
        TextView warning = text(activity, "", 13, GOLD, false);
        if (noGuess) {
            warning.setGravity(Gravity.CENTER);
            warning.setMinLines(2);
            dialog.addContent(warning, 6);
        }
        Runnable update = () -> {
            int width = widthPicker.value(), height = heightPicker.value(), percent = ratePicker.value();
            int cells = width * height;
            int mines = Minefield.minesForRate(width, height, percent);
            preview.setText("实际 " + mines + " 雷 · 难度参考 " + estimatedDifficulty(cells, mines));
            if (noGuess) {
                String message = null;
                if (cells - mines > 1) {
                    if (cells == 4) message = "这个雷数无法生成 2×2 无猜棋盘。";
                    else if (percent >= NoGuess.warningRate(cells))
                        message = "当前设置下，无猜棋盘可能生成失败或超时。";
                }
                warning.setText(message);
                warning.setVisibility(message == null ? View.INVISIBLE : View.VISIBLE);
            }
        };
        widthPicker.onChange = heightPicker.onChange = ratePicker.onChange = update;
        update.run();

        dialog.show("应用", () -> onApply.apply(widthPicker.value(), heightPicker.value(), ratePicker.value()));
        return dialog;
    }

    private static Wheel addWheel(LinearLayout row, String label, int min, int max, int value, String suffix) {
        Context context = row.getContext();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        if (row.getChildCount() > 0) params.leftMargin = dp(context, 8);
        row.addView(column, params);

        TextView heading = text(context, label, 14, QUIET, true);
        heading.setGravity(Gravity.CENTER);
        heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(heading);
        Wheel wheel = new Wheel(context, label, min, max, value, suffix);
        LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(MATCH_PARENT, dp(context, 118));
        wheelParams.topMargin = dp(context, 6);
        column.addView(wheel, wheelParams);
        return wheel;
    }

    private static final class Wheel extends View {
        private final int min, max, rowHeight, slop, minFling, maxFling;
        private final String suffix;
        private final OverScroller scroller;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private VelocityTracker velocity;
        private int pointer = -1;
        private float offset, downY, lastY, wheelRemainder;
        private boolean dragging;
        Runnable onChange;

        Wheel(Context context, String label, int min, int max, int value, String suffix) {
            super(context);
            this.min = min;
            this.max = max;
            this.suffix = suffix;
            rowHeight = dp(context, 38);
            ViewConfiguration config = ViewConfiguration.get(context);
            slop = config.getScaledTouchSlop();
            minFling = config.getScaledMinimumFlingVelocity();
            maxFling = config.getScaledMaximumFlingVelocity();
            scroller = new OverScroller(context);
            offset = offsetFor(value);
            paint.setTextAlign(Paint.Align.CENTER);
            setContentDescription(label);
            setFocusable(true);
            setClickable(true);
        }

        int value() { return min + Math.round(offset / rowHeight); }

        private int offsetFor(int value) {
            return (Math.max(min, Math.min(max, value)) - min) * rowHeight;
        }

        private String display(int value) { return Integer.toString(value) + suffix; }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float middle = getHeight() / 2f, halfRow = rowHeight / 2f;
            float inset = dp(getContext(), 4), radius = dp(getContext(), 12);
            paint.setColor(isFocused() ? GOLD : EDGE);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            paint.setColor(INK);
            canvas.drawRoundRect(1, 1, getWidth() - 1, getHeight() - 1, radius, radius, paint);
            paint.setColor(PANEL);
            canvas.drawRoundRect(inset, middle - halfRow, getWidth() - inset, middle + halfRow, radius, radius, paint);
            int selected = value();
            for (int item = Math.max(min, selected - 2); item <= Math.min(max, selected + 2); item++) {
                float y = middle + offsetFor(item) - offset;
                boolean centered = item == selected;
                paint.setTypeface(centered ? MEDIUM : Typeface.DEFAULT);
                paint.setTextSize(dp(getContext(), centered ? 23 : 18));
                paint.setColor(centered ? GOLD : QUIET);
                if (!centered) {
                    float fade = Math.max(0, 1 - Math.abs(y - middle) / (2 * rowHeight));
                    paint.setAlpha(Math.round(255 * fade));
                }
                canvas.drawText(display(item), getWidth() / 2f, y - (paint.ascent() + paint.descent()) / 2, paint);
            }
        }

        private void updateOffset(float next) {
            int previous = value();
            offset = Math.max(0, Math.min(offsetFor(max), next));
            if (value() != previous && onChange != null) onChange.run();
            invalidate();
        }

        private boolean stepTo(int target) {
            int previous = value();
            scroller.forceFinished(true);
            updateOffset(offsetFor(target));
            if (value() == previous) return false;
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            return true;
        }

        private void settle() {
            int target = offsetFor(value()), start = Math.round(offset);
            if (start == target) {
                updateOffset(target);
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            } else {
                scroller.startScroll(0, start, 0, target - start, 120);
                postInvalidateOnAnimation();
            }
        }

        @Override public void computeScroll() {
            if (!scroller.computeScrollOffset()) return;
            updateOffset(scroller.getCurrY());
            if (scroller.isFinished()) settle();
            else postInvalidateOnAnimation();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                scroller.forceFinished(true);
                clearTouch();
                pointer = event.getPointerId(0);
                downY = lastY = event.getY();
                dragging = false;
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL && pointer != -1) {
                clearTouch();
                settle();
                return true;
            }
            int index = event.findPointerIndex(pointer);
            if (index < 0) return true;
            velocity.addMovement(event);
            float y = event.getY(index);
            if (action == MotionEvent.ACTION_MOVE) {
                if (!dragging && Math.abs(y - downY) <= slop) return true;
                dragging = true;
                updateOffset(offset + lastY - y);
                lastY = y;
            } else if (action == MotionEvent.ACTION_UP ||
                    (action == MotionEvent.ACTION_POINTER_UP && index == event.getActionIndex())) {
                if (dragging) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    int speed = -Math.round(velocity.getYVelocity(pointer));
                    if (Math.abs(speed) >= minFling) {
                        scroller.fling(0, Math.round(offset), 0, speed, 0, 0, 0, offsetFor(max));
                        postInvalidateOnAnimation();
                    } else settle();
                } else {
                    int delta = Math.round((y - getHeight() / 2f) / rowHeight);
                    stepTo(value() + delta);
                    performClick();
                }
                clearTouch();
            }
            return true;
        }

        private void clearTouch() {
            if (velocity != null) velocity.recycle();
            velocity = null;
            pointer = -1;
        }

        @Override protected void onDetachedFromWindow() {
            clearTouch();
            scroller.forceFinished(true);
            super.onDetachedFromWindow();
        }

        @Override public boolean onGenericMotionEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float rotation = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (rotation != 0) {
                    wheelRemainder += rotation;
                    int steps = (int)wheelRemainder;
                    wheelRemainder -= steps;
                    if (steps != 0) stepTo(value() - steps);
                    return true;
                }
            }
            return super.onGenericMotionEvent(event);
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && stepTo(value() - 1)) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && stepTo(value() + 1)) return true;
            return super.onKeyDown(keyCode, event);
        }

        @Override public CharSequence getAccessibilityClassName() { return NumberPicker.class.getName(); }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setText(display(value()));
            info.setContentDescription(getContentDescription() + "，" + display(value()));
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, max, value()));
            info.setScrollable(true);
            if (value() < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (value() > min) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (Build.VERSION.SDK_INT >= 24)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return stepTo(value() + 1);
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return stepTo(value() - 1);
            if (Build.VERSION.SDK_INT >= 24 && arguments != null &&
                    action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                return stepTo(Math.round(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value())));
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }
}
