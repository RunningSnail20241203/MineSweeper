package com.local.minesweeper;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static com.local.minesweeper.GameView.*;

final class GameDialog {
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout panel;

    GameDialog(Activity activity, String title) {
        this.activity = activity;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(title);
        dialog.setCanceledOnTouchOutside(true);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(activity, 18);
        panel.setPadding(inset, inset, inset, inset);
        panel.setBackground(rounded(activity, DARK, EDGE, 24));
        TextView heading = text(activity, title, 22, CREAM, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        panel.addView(heading);
    }

    void addContent(View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.topMargin = dp(activity, margin);
        panel.addView(view, params);
    }

    void dismiss() {
        dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void setOnDismissListener(Runnable onDismiss) {
        dialog.setOnDismissListener(ignored -> onDismiss.run());
    }

    void show(String primaryLabel, Runnable onConfirm) {
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        addContent(actions, 12);
        Button cancel = addButton(actions, "取消", false);
        Button confirm = addButton(actions, primaryLabel, true);
        cancel.setOnClickListener(view -> dialog.dismiss());
        confirm.setOnClickListener(view -> {
            onConfirm.run();
            dialog.dismiss();
        });
        show();
    }

    void show() {
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.66f);
        dialog.show();
        int windowWidth = activity.findViewById(android.R.id.content).getWidth();
        if (windowWidth == 0) windowWidth = activity.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min(Math.max(1, windowWidth - dp(activity, 32)), dp(activity, 388)),
                WRAP_CONTENT);
    }

    static Button addButton(LinearLayout row, String label, boolean primary) {
        Context context = row.getContext();
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(16);
        button.setTypeface(MEDIUM);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        styleButton(button, primary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 48), 1);
        if (row.getChildCount() > 0) params.leftMargin = dp(context, 10);
        row.addView(button, params);
        return button;
    }

    static void styleButton(Button button, boolean primary) {
        Context context = button.getContext();
        button.setTextColor(primary ? INK : CREAM);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_pressed},
                rounded(context, primary ? 0xffddbb70 : TILE, primary ? 0 : EDGE, 14));
        background.addState(new int[] {android.R.attr.state_focused},
                rounded(context, primary ? GOLD : PANEL, primary ? CREAM : GOLD, 14));
        background.addState(new int[0], rounded(context, primary ? GOLD : PANEL, primary ? 0 : EDGE, 14));
        button.setBackground(background);
    }

    static TextView text(Context context, String label, int size, int color, boolean medium) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (medium) view.setTypeface(MEDIUM);
        return view;
    }

    private static GradientDrawable rounded(Context context, int color, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        if (border != 0) drawable.setStroke(dp(context, 1), border);
        return drawable;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
