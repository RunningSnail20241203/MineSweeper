package com.local.minesweeper;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tapsdk.lc.LCRanking;
import com.tapsdk.lc.LCUser;

import static com.local.minesweeper.GameDialog.addButton;
import static com.local.minesweeper.GameDialog.dp;
import static com.local.minesweeper.GameDialog.styleButton;
import static com.local.minesweeper.GameDialog.text;
import static com.local.minesweeper.GameView.CREAM;
import static com.local.minesweeper.GameView.GOLD;
import static com.local.minesweeper.GameView.QUIET;
import static com.local.minesweeper.GameView.TILE;

final class TapLeaderboardDialog {
    private final Activity activity;
    private final TapLeaderboard service;
    private final GameDialog dialog;
    private final LeaderboardAvatars avatars = new LeaderboardAvatars();
    private final Button[] modes = new Button[2];
    private final Button[] levels = new Button[3];
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final TextView personal;
    private int mode;
    private int level;
    private int request;

    TapLeaderboardDialog(Activity activity, TapLeaderboard service, int board, Runnable authorizeProfile) {
        this.activity = activity;
        this.service = service;
        mode = board / 3;
        level = board % 3;
        dialog = new GameDialog(activity, "排行榜");
        dialog.setOnDismissListener(avatars::close);
        dialog.addContent(filters(new String[] {"经典", "无猜"}, modes, true), 12);
        dialog.addContent(filters(new String[] {"初级", "中级", "高级"}, levels, false), 8);

        personal = text(activity, "我的成绩", 14, GOLD, true);
        dialog.addContent(personal, 12);

        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(activity);
        scroll.addView(rows, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int available = activity.findViewById(android.R.id.content).getHeight();
        scroll.setMinimumHeight(dp(activity, 88));
        LinearLayout list = new LinearLayout(activity);
        int height = Math.max(dp(activity, 88), Math.min(dp(activity, 320), available - dp(activity, 305)));
        list.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, height);
        dialog.addContent(list, 4);

        LinearLayout actions = new LinearLayout(activity);
        if (authorizeProfile != null) {
            addButton(actions, "授权资料", false).setOnClickListener(view -> authorizeProfile.run());
        }
        addButton(actions, "刷新", false).setOnClickListener(view -> load());
        addButton(actions, "关闭", true).setOnClickListener(view -> dismiss());
        dialog.addContent(actions, 12);
    }

    void show() {
        dialog.show();
        load();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    int selectedBoard() {
        return mode * 3 + level;
    }

    void dismiss() {
        request++;
        dialog.dismiss();
    }

    private LinearLayout filters(String[] names, Button[] buttons, boolean modeFilter) {
        LinearLayout row = new LinearLayout(activity);
        for (int i = 0; i < names.length; i++) {
            int index = i;
            buttons[i] = addButton(row, names[i], false);
            buttons[i].setOnClickListener(view -> {
                if (modeFilter) mode = index;
                else level = index;
                load();
            });
        }
        return row;
    }

    private void load() {
        avatars.cancelQueued();
        for (int i = 0; i < modes.length; i++) styleButton(modes[i], i == mode);
        for (int i = 0; i < levels.length; i++) styleButton(levels[i], i == level);
        personal.setText("我的成绩");
        showMessage("正在加载…");
        int current = ++request;
        service.load(selectedBoard(), (top, mine, error) -> {
            if (current != request || !isShowing()) return;
            if (error != null) {
                showMessage(error);
                return;
            }
            personal.setText(mine == null ? "我的成绩 · 暂未上榜" :
                    "我的成绩 · 第 " + (mine.getRank() + 1) + " 名 · " + time(mine));
            if (top.isEmpty()) {
                showMessage("还没有通关成绩");
                return;
            }
            rows.removeAllViews();
            for (LCRanking result : top) addResult(result);
        });
    }

    private void showMessage(String message) {
        rows.removeAllViews();
        TextView label = text(activity, message, 14, QUIET, false);
        label.setPadding(0, dp(activity, 12), 0, 0);
        rows.addView(label);
        scroll.scrollTo(0, 0);
    }

    private void addResult(LCRanking ranking) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView rank = text(activity, Integer.toString(ranking.getRank() + 1), 14, GOLD, true);
        row.addView(rank, dp(activity, 30), ViewGroup.LayoutParams.WRAP_CONTENT);
        LCUser user = ranking.getUser();
        ImageView avatar = new ImageView(activity);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(TILE);
        avatar.setBackground(circle);
        avatar.setClipToOutline(true);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int avatarSize = dp(activity, 30);
        LinearLayout.LayoutParams avatarLayout = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLayout.rightMargin = dp(activity, 9);
        row.addView(avatar, avatarLayout);
        Object avatarUrl = user == null ? null : user.get(TapLeaderboard.AVATAR_FIELD);
        avatars.bind(avatar, avatarUrl instanceof String ? (String) avatarUrl : null);

        String nickname = user == null ? null : user.getString("nickname");
        if (nickname != null) nickname = nickname.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        TextView player = text(activity, TextUtils.isEmpty(nickname) ? "匿名玩家" : nickname, 15, CREAM, false);
        player.setSingleLine();
        player.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(player, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView score = text(activity, time(ranking), 14, QUIET, true);
        score.setGravity(Gravity.END);
        row.addView(score, dp(activity, 105), ViewGroup.LayoutParams.WRAP_CONTENT);
        rows.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 42));
    }

    private static String time(LCRanking ranking) {
        return GameView.rankedTime(Math.round(ranking.getStatisticValue()));
    }
}
