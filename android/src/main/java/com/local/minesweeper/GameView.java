package com.local.minesweeper;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class GameView extends View {
    private enum Scene { SPLASH, ENTRY, HOME, BOARD, RESULT }

    interface RankStatus { void update(String text); }
    interface RankedWin { void submit(boolean noGuess, int preset, long time, RankStatus status); }

    private static final float WIDTH = 400;
    static final int INK = 0xff102720, DARK = 0xff17362d, PANEL = 0xff204238;
    static final int TILE = 0xff31574a, EDGE = 0xff467163, CREAM = 0xfff3f3df;
    static final int QUIET = 0xff9fbab0, GOLD = 0xffefd184;
    private static final int MINT = 0xffaee3c1;
    private static final int BLUE = 0xff86d7f5, RED = 0xffff977d, OPEN = 0xffdfead2;
    private static final int[] NUMBERS = { OPEN, 0xff3176a6, 0xff307657, 0xffb65745,
            0xff7754a9, 0xff976530, 0xff266e70, 0xff483c39, 0xff4f665a };
    private static final String[] NUMBER_TEXT = { "", "1", "2", "3", "4", "5", "6", "7", "8" };
    private static final String[] LEVELS = { "初级", "中级", "高级", "自定义" };
    private static final int[][] PRESETS = { {9, 9, 10}, {16, 16, 40}, {16, 30, 99} };
    private static final int SAVE_VERSION = 1;
    private static final long FLAG_HOLD_MILLIS = 320;
    private static final int DETAIL_CELL_SIZE = 20;
    private static final int OVERVIEW_CELL_SIZE = 4;

    private final Activity activity;
    private final Paint loginArtworkPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap tapTapLoginArtwork;
    private Runnable tapTapAccountAction;
    private Runnable privacyPolicyAction;
    private Runnable leaderboardAction;
    private RankedWin rankedWin;
    private String leaderboardAccount;
    private String boardOwner;
    private String leaderboardStatus;
    private boolean tapTapSignedIn;
    private boolean gameplayAllowed = true;
    private String entryStatus;
    private GameDialog gameDialog;
    private final String appName;
    private final SharedPreferences preferences;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF gameArea = new RectF();
    private final RectF overviewBounds = new RectF();
    private final ArrayList<Hit> hits = new ArrayList<>();
    private final ArrayList<Integer> changed = new ArrayList<>();
    private final Random random = new Random();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScaleGestureDetector pinch;
    private final Runnable hold = this::longPress;
    private final Runnable clockTick = this::invalidate;

    private Scene scene = Scene.SPLASH, previous;
    private long sceneAt = SystemClock.uptimeMillis();
    private int selection, customWidth = 12, customHeight = 16, customMineRate = 17;
    private int customMines = Minefield.minesForRate(customWidth, customHeight, customMineRate);
    private boolean noGuess, flagTool, running, mixingInput, boardFadeIn;
    private Minefield field;
    private long accumulated, started, entranceAt, resultAt, explosionAt, activeUntil, generationVersion;
    private long[] reveals = new long[0], flags = new long[0];
    private byte[] entryRings = new byte[0];
    private Bitmap overview;
    private int[] overviewRow;
    private float entryDelay;
    private Future<?> generation;
    private String generationNote;
    private float density = 1, viewportLeft, height = 720, unit = 1, zoom = 1, left, top, pinchX, pinchY;
    private long fitAt;
    private float fitFromZoom, fitFromX, fitFromY;
    private LinearGradient backdrop;
    private Hit armedHit;
    private int pressed = -1, rebounding = -1;
    private long pressedAt, releasedAt;
    private boolean dragging, boardGesture, moveArmed;
    private float downX, downY, dragLeft, dragTop;

    GameView(Activity activity) {
        super(activity);
        this.activity = activity;
        appName = activity.getString(activity.getApplicationInfo().labelRes);
        preferences = activity.getSharedPreferences("minesweeper", Activity.MODE_PRIVATE);
        setFocusable(true);
        setClickable(true);
        restore();
        if (field != null) {
            selection = field.preset;
            noGuess = field.noGuess;
            if (selection == 3) {
                customWidth = field.columns;
                customHeight = field.rows;
                customMines = field.mines;
                customMineRate = field.mineRate;
            }
        }
        pinch = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector event) {
                if (!boardGesture || scene != Scene.BOARD) return false;
                stopPress();
                dragging = true;
                fitAt = 0;
                pinchX = toBoardX(event.getFocusX());
                pinchY = event.getFocusY() / density;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector event) {
                float pointX = toBoardX(event.getFocusX()), pointY = event.getFocusY() / density;
                float next = clamp(zoom * event.getScaleFactor(), .72f, Math.max(1, 110 / unit));
                float ratio = next / zoom;
                left = pointX - (pinchX - left) * ratio;
                top = pointY - (pinchY - top) * ratio;
                zoom = next;
                pinchX = pointX;
                pinchY = pointY;
                constrainPan();
                invalidate();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        setContentDescription(appName);
    }

    void setTapTapActions(Bitmap loginArtwork, Runnable accountAction, Runnable privacyAction) {
        tapTapLoginArtwork = loginArtwork;
        tapTapAccountAction = accountAction;
        privacyPolicyAction = privacyAction;
        invalidate();
    }

    void setTapTapSignedIn(boolean signedIn) {
        tapTapSignedIn = signedIn;
        invalidate();
    }

    void setLeaderboardActions(Runnable open, RankedWin onWin) {
        leaderboardAction = open;
        rankedWin = onWin;
        invalidate();
    }

    void setLeaderboardAccount(String account) {
        leaderboardAccount = account;
    }

    int selectedLeaderboard() {
        return (noGuess ? 3 : 0) + (selection < PRESETS.length ? selection : 0);
    }

    void clearRankedBoard() {
        boardOwner = null;
        leaderboardStatus = null;
        save();
    }

    void allowGameplay() {
        gameplayAllowed = true;
        if (scene == Scene.ENTRY || scene == Scene.SPLASH) transition(Scene.HOME);
    }

    void lockGameplay(String status) {
        entryStatus = status;
        if (!gameplayAllowed) {
            invalidate();
            return;
        }
        gameplayAllowed = false;
        dismissGameDialog();
        removeCallbacks(clockTick);
        stopPress();
        cancelGeneration();
        pauseClock();
        if (scene == Scene.BOARD || scene == Scene.RESULT) save();
        transition(Scene.ENTRY);
        previous = null;
        mixingInput = false;
        invalidate();
    }

    private Scene openingScene() {
        return tapTapAccountAction == null ? Scene.HOME : Scene.ENTRY;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w == 0 || h == 0) return;
        density = Math.min(w / WIDTH, h / 552f);
        viewportLeft = (w - WIDTH * density) / 2;
        height = h / density;
        gameArea.set(20, 149, WIDTH - 20, Math.max(210, height - 114));
        backdrop = new LinearGradient(0, 0, WIDTH, height, 0xff203f34, INK, Shader.TileMode.CLAMP);
        fitImmediately();
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        removeCallbacks(clockTick);
        if (scene == Scene.SPLASH && now - sceneAt >= 940) transition(openingScene());
        if (scene == Scene.BOARD && resultAt > 0 && now >= resultAt) {
            resultAt = 0;
            transition(Scene.RESULT);
        }
        if (fitAt > 0) advanceFit(now);
        hits.clear();
        canvas.drawColor(INK);
        canvas.save();
        canvas.translate(viewportLeft, 0);
        canvas.scale(density, density);
        paint.setColor(Color.WHITE);
        paint.setShader(backdrop);
        canvas.drawRect(0, 0, WIDTH, height, paint);
        paint.setShader(null);
        float phase = progress(now - sceneAt, 285);
        mixingInput = (previous != null || boardFadeIn) && phase < 1;
        if (mixingInput) {
            float easing = ease(phase);
            int layer = canvas.saveLayerAlpha(0, 0, WIDTH, height, (int)(255 * (1 - easing)));
            canvas.translate(0, -12 * easing);
            if (previous != null) drawScene(canvas, previous, now, false);
            canvas.restoreToCount(layer);
            layer = canvas.saveLayerAlpha(0, 0, WIDTH, height, (int)(255 * easing));
            canvas.translate(0, 22 * (1 - easing));
            drawScene(canvas, scene, now, true);
            canvas.restoreToCount(layer);
        } else {
            if (!mixingInput) {
                previous = null;
                boardFadeIn = false;
            }
            drawScene(canvas, scene, now, true);
        }
        canvas.restore();
        if (!running) return;
        if (scene == Scene.SPLASH || mixingInput || now < activeUntil || fitAt > 0 ||
                (scene == Scene.RESULT && now - sceneAt < 1040))
            postInvalidateOnAnimation();
        else if (scene == Scene.BOARD && started != 0)
            postDelayed(clockTick, 1000 - elapsed(now) % 1000);
    }

    private void drawScene(Canvas canvas, Scene page, long now, boolean interactive) {
        switch (page) {
            case SPLASH: drawSplash(canvas, now); break;
            case ENTRY: drawEntry(canvas, interactive); break;
            case HOME: drawHome(canvas, interactive); break;
            case BOARD: drawBoardScene(canvas, now, interactive); break;
            case RESULT:
                drawBoardScene(canvas, now, false);
                drawResult(canvas, now, interactive);
                break;
        }
    }

    private void drawSplash(Canvas canvas, long now) {
        float mid = height * .43f;
        float amount = scene == Scene.SPLASH ? ease(progress(now - sceneAt, 530)) : 1;
        halo(canvas, WIDTH / 2, mid, 138, color(MINT, .16f * amount));
        badge(canvas, WIDTH / 2, mid - 12 * (1 - amount), 43 + 12 * amount, MINT, false);
        text(canvas, appName, WIDTH / 2, mid + 108, 48, color(CREAM, amount), true, true);
    }

    private void drawEntry(Canvas canvas, boolean interactive) {
        float top = height / 2 - 66;
        text(canvas, appName, WIDTH / 2, top - 16, 40, CREAM, true, true);
        text(canvas, entryStatus, WIDTH / 2, top + 25, 15, QUIET, false, true);
        drawTapTapEntry(canvas, top + 55, interactive);
        button(canvas, 267, 22, 378, 64, "隐私协议", false, interactive, privacyPolicyAction);
    }

    private void drawHome(Canvas canvas, boolean interactive) {
        float top = Math.max(24, Math.min(72, (height - 500) * .32f));
        halo(canvas, 305, top + 68, 154, color(MINT, .13f));
        drawPattern(canvas, 217, top + 8);
        text(canvas, appName, 28, top + 72, 44, CREAM, true, false);
        if (tapTapAccountAction != null) {
            button(canvas, 142, top - 12, 254, top + 29, "TapTap 账号", false, interactive, tapTapAccountAction);
            button(canvas, 267, top - 12, 378, top + 29, "隐私协议", false, interactive, privacyPolicyAction);
        }

        float card = top + 117;
        round(canvas, 22, card, 378, card + 244, 24, DARK);
        outline(canvas, 22, card, 378, card + 244, 24, EDGE, 1);
        text(canvas, "模式", 41, card + 35, 15, QUIET, true, false);
        button(canvas, 40, card + 48, 194, card + 92, "经典", !noGuess, interactive, () -> chooseMode(false));
        button(canvas, 206, card + 48, 360, card + 92, "无猜", noGuess, interactive, () -> chooseMode(true));
        text(canvas, "难度", 41, card + 126, 15, QUIET, true, false);
        for (int i = 0; i < LEVELS.length; i++) {
            final int index = i;
            float x = 40 + i * 81;
            button(canvas, x, card + 138, x + 76, card + 182, LEVELS[i], selection == i, interactive,
                    () -> selectLevel(index));
        }
        int[] config = configuration();
        text(canvas, config[0] + " × " + config[1] + "   ·   " + config[2] + " 雷",
                WIDTH / 2, card + 219, 17, CREAM, false, true);
        button(canvas, 22, card + 263, 378, card + 321, "开始新游戏", true, interactive, this::newFromMenu);
        if (field != null) {
            button(canvas, 22, card + 333, leaderboardAction == null ? 378 : 194, card + 383,
                    field.isOver() ? "查看上局" : "继续上局",
                    false, interactive, this::showBoard);
        }
        if (leaderboardAction != null) {
            button(canvas, field == null ? 22 : 206, card + 333, 378, card + 383, "排行榜",
                    false, interactive, leaderboardAction);
        }
    }

    private void drawTapTapEntry(Canvas canvas, float top, boolean interactive) {
        float width = 270;
        float centerX = WIDTH / 2;
        float left = centerX - width / 2;
        float right = centerX + width / 2;
        float bottom = top + 50;
        if (tapTapSignedIn || tapTapLoginArtwork == null) {
            button(canvas, left, top, right, bottom, tapTapSignedIn ? "TapTap 账号" : "TapTap 登录",
                    false, interactive, tapTapAccountAction);
            return;
        }
        float imageHeight = width * tapTapLoginArtwork.getHeight() / tapTapLoginArtwork.getWidth();
        float centerY = (top + bottom) / 2;
        canvas.drawBitmap(tapTapLoginArtwork, null, new RectF(left, centerY - imageHeight / 2,
                right, centerY + imageHeight / 2), loginArtworkPaint);
        if (interactive) hits.add(new Hit(new RectF(left, top, right, bottom), "TapTap 登录", tapTapAccountAction));
    }

    private void drawPattern(Canvas canvas, float x, float y) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                float l = x + col * 38, t = y + row * 38;
                round(canvas, l, t, l + 33, t + 33, 8, color((row + col) % 3 == 1 ? MINT : EDGE, .22f));
            }
        }
        flag(canvas, x + 93, y + 53, 41, GOLD);
    }

    private void drawBoardScene(Canvas canvas, long now, boolean interactive) {
        if (field == null) return;
        text(canvas, appName, 24, 52, 34, CREAM, true, false);
        text(canvas, (field.noGuess ? "无猜" : "经典") + " · " + LEVELS[field.preset], 25, 74, 14, QUIET, false, false);
        button(canvas, 303, 24, 378, 64, "菜单", false, interactive, this::showHome);
        metric(canvas, 20, 91, 193, 136, "用时", timeText(elapsed(now)));
        metric(canvas, 207, 91, 380, 136, "剩余雷数", Integer.toString(field.mines - field.flags));
        drawGrid(canvas, now);
        if (field.isOver()) {
            if (resultAt == 0) {
                button(canvas, 20, height - 94, 380, height - 43, "查看结算", true, interactive,
                        () -> transition(Scene.RESULT));
            }
        } else {
            button(canvas, 20, height - 95, 146, height - 43, "翻格", !flagTool, interactive,
                    () -> chooseTool(false));
            button(canvas, 155, height - 95, 281, height - 43, "插旗", flagTool, interactive,
                    () -> chooseTool(true));
            button(canvas, 291, height - 95, 380, height - 43, "全盘", false, interactive, this::startFit);
            text(canvas, flagTool ? "点按插旗 · 点数字批量标雷" : "点按翻格 · 长按插旗 · 双指缩放",
                    WIDTH / 2, height - 20, 13, QUIET, false, true);
        }
        if (generation != null || generationNote != null) {
            float by = gameArea.bottom - 47;
            round(canvas, 49, by, 351, by + 34, 14, 0xf2213e35);
            text(canvas, generation != null ? "正在生成无猜棋盘…" : generationNote,
                    WIDTH / 2, by + 23, 14, CREAM, false, true);
        }
    }

    private void metric(Canvas canvas, float l, float t, float r, float b, String label, String value) {
        round(canvas, l, t, r, b, 13, PANEL);
        text(canvas, label, l + 12, t + 28, 13, QUIET, false, false);
        text(canvas, value, r - 46, t + 30, 22, CREAM, true, true);
    }

    private void button(Canvas canvas, float l, float t, float r, float b, String label, boolean primary,
                        boolean interactive, Runnable action) {
        boolean down = interactive && armedHit != null && armedHit.label.equals(label);
        int fill = primary ? GOLD : PANEL;
        float displacement = down ? 2 : 0;
        round(canvas, l, t + displacement, r, b - displacement, 14, down ? mix(fill, INK, .13f) : fill);
        if (!primary) outline(canvas, l, t + displacement, r, b - displacement, 14, EDGE, 1);
        text(canvas, label, (l + r) / 2, (t + b) / 2 + 6, 16, primary ? INK : CREAM, true, true);
        if (interactive) hits.add(new Hit(new RectF(l, t, r, b), label, action));
    }

    private void drawGrid(Canvas canvas, long now) {
        round(canvas, gameArea.left, gameArea.top, gameArea.right, gameArea.bottom, 16, 0xff102d25);
        canvas.save();
        path.reset();
        path.addRoundRect(gameArea, 16, 16, Path.Direction.CW);
        canvas.clipPath(path);
        float cellSize = unit * zoom;
        if (largeBoard() && (cellSize < OVERVIEW_CELL_SIZE || zoom <= 1)) drawOverview(canvas, cellSize);
        else drawTiles(canvas, now, cellSize);
        canvas.restore();
        outline(canvas, gameArea.left, gameArea.top, gameArea.right, gameArea.bottom, 16, 0xff396151, 1);
    }

    private void drawOverview(Canvas canvas, float cellSize) {
        if (overview == null) {
            overview = Bitmap.createBitmap(field.columns, field.rows, Bitmap.Config.ARGB_8888);
            overviewRow = new int[field.columns];
            if (field.state == Minefield.READY && field.flags == 0) overview.eraseColor(TILE);
            else refreshOverview();
        }
        overviewBounds.set(left, top, left + field.columns * cellSize, top + field.rows * cellSize);
        canvas.drawBitmap(overview, null, overviewBounds, null);
    }

    private int overviewColor(int cell) {
        if (cell == field.exploded) return RED;
        if (field.cells[cell] == Minefield.FLAGGED)
            return field.state == Minefield.LOST && field.numbers[cell] != Minefield.MINE ? RED : GOLD;
        if (field.state == Minefield.LOST && field.numbers[cell] == Minefield.MINE) return QUIET;
        return field.cells[cell] == Minefield.OPEN ? OPEN : TILE;
    }

    private void refreshOverview() {
        for (int row = 0; row < field.rows; row++) {
            int first = row * field.columns;
            for (int col = 0; col < field.columns; col++) overviewRow[col] = overviewColor(first + col);
            overview.setPixels(overviewRow, 0, field.columns, 0, row, field.columns, 1);
        }
    }

    private void updateOverview() {
        if (overview == null) return;
        if (field.isOver() || changed.size() > 4096) {
            refreshOverview();
        } else {
            for (int cell : changed)
                overview.setPixel(cell % field.columns, cell / field.columns, overviewColor(cell));
        }
    }

    private void drawTiles(Canvas canvas, long now, float cellSize) {
        float gap = Math.min(cellSize * .24f, Math.max(.44f, cellSize * .083f));
        int firstX = Math.max(0, (int)Math.floor((gameArea.left - left) / cellSize));
        int firstY = Math.max(0, (int)Math.floor((gameArea.top - top) / cellSize));
        int endX = Math.min(field.columns, (int)Math.ceil((gameArea.right - left) / cellSize));
        int endY = Math.min(field.rows, (int)Math.ceil((gameArea.bottom - top) / cellSize));
        boolean entering = entranceAt > 0 && now - entranceAt < 535;
        float mineAmount = field.state == Minefield.LOST ?
                (explosionAt > 0 ? progress(now - explosionAt - 445, 115) : 1) : 0;
        int[] pressGroup = moveArmed && field.cells[pressed] == Minefield.OPEN && canUseTool(pressed)
                ? field.neighborOffsets(pressed) : null;
        float pressedAmount = .30f + .70f * ease(progress(now - pressedAt, 75));
        float reboundAmount = .65f * (1 - ease(progress(now - releasedAt, 120)));
        for (int y = firstY; y < endY; y++) {
            for (int x = firstX; x < endX; x++) {
                int cell = y * field.columns + x;
                float entrance = 1;
                if (entering) {
                    float anim = now - entranceAt - entryRings[cell] * entryDelay;
                    if (anim < 0) continue;
                    entrance = progress(anim, 158);
                }
                long revealedAt = reveals.length == 0 ? 0 : reveals[cell];
                float open = field.cells[cell] == Minefield.OPEN ?
                        (revealedAt == 0 ? 1 : ease(progress(now - revealedAt, 91))) : 0;
                float flash = revealedAt == 0 || now < revealedAt ? 0 :
                        (1 - progress(now - revealedAt, 110)) * progress(now - revealedAt, 25);
                boolean hit = field.exploded == cell && field.state == Minefield.LOST;
                boolean mine = hit || (mineAmount > 0 && field.numbers[cell] == Minefield.MINE);
                int fill = mix(TILE, OPEN, open);
                if (mine) fill = hit ? mix(RED, 0xffae5046, .35f) : mix(TILE, OPEN, mineAmount);
                if (flash > 0) fill = mix(fill, GOLD, flash * .47f);
                float sink = cell == pressed ? pressedAmount : cell == rebounding ? reboundAmount : 0;
                if (pressGroup != null && field.cells[cell] == Minefield.COVERED &&
                        Minefield.contains(pressGroup, cell - pressed))
                    sink = pressedAmount * .65f;
                if (sink > 0) fill = mix(fill, GOLD, .19f * sink);
                if (entrance < 1) fill = mix(fill, MINT, .25f * (1 - entrance));
                float l = left + x * cellSize + gap / 2, t = top + y * cellSize + gap / 2;
                float tile = cellSize - gap, cx = l + tile / 2, cy = t + tile / 2;
                float remaining = 1 - entrance;
                float scale = 1 - .63f * remaining * remaining * (2.6f * remaining - 1.6f);
                float half = tile * scale * (.5f - .07f * sink);
                cy += cellSize * .053f * sink;
                float radius = Math.min(9, cellSize / 6);
                if (cellSize < 7) {
                    paint.setColor(fill);
                    canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
                } else round(canvas, cx - half, cy - half, cx + half, cy + half, radius, fill);
                if (cellSize >= 12 && (open < .99f || sink > 0)) {
                    int edge = sink > 0 ? color(GOLD, .58f * sink) : color(EDGE, .9f * (1 - open));
                    outline(canvas, cx - half, cy - half, cx + half, cy + half, radius, edge, .9f);
                }
                drawCellMark(canvas, cell, cx, cy, cellSize, now, open, mine, hit, mineAmount);
            }
        }
        if (explosionAt > 0 && now - explosionAt < 560) {
            int hit = field.exploded;
            float cx = left + (hit % field.columns + .5f) * cellSize;
            float cy = top + (hit / field.columns + .5f) * cellSize;
            sparks(canvas, cx, cy, Math.max(12, Math.min(49, cellSize)), now - explosionAt);
        }
    }

    private void drawCellMark(Canvas canvas, int cell, float x, float y, float size, long now,
                              float open, boolean mine, boolean hit, float mineAmount) {
        long flaggedAt = flags.length == 0 ? 0 : flags[cell];
        long age = flaggedAt == 0 ? Long.MAX_VALUE : now - flaggedAt;
        boolean planted = field.cells[cell] == Minefield.FLAGGED;
        if (planted || age < 170) {
            float opacity = planted ? 1 : 1 - ease(progress(age, 170));
            float shift = 0, scale = planted ? 1 : .18f + .82f * opacity;
            if (planted && age < 235) {
                float drop = progress(age, 108);
                float rebound = progress(age - 108, 127);
                shift = (-.34f * (1 - drop * drop) - .105f * (float)Math.sin(rebound * Math.PI)) * size;
                scale = .82f + .18f * drop + .085f * (float)Math.sin(rebound * Math.PI);
            }
            int tint = field.state == Minefield.LOST && field.numbers[cell] != Minefield.MINE ?
                    mix(GOLD, RED, mineAmount) : GOLD;
            if (size >= 12) flag(canvas, x, y + shift, size * .65f * scale, color(tint, opacity));
            else circle(canvas, x, y + shift, Math.max(.6f, size * .30f * scale), color(tint, opacity));
        } else if (mine) {
            float opacity = hit && explosionAt > 0 && now - explosionAt < 48 ? 1 : mineAmount;
            if (opacity > 0) mine(canvas, x, y, size * .50f, color(INK, opacity));
        } else if (size >= 9 && open > 0 && field.numbers[cell] > 0) {
            text(canvas, NUMBER_TEXT[field.numbers[cell]], x, y + size * .155f, size * .46f,
                    color(NUMBERS[field.numbers[cell]], open), true, true);
        }
    }

    private void drawResult(Canvas canvas, long now, boolean interactive) {
        boolean won = field.state == Minefield.WON;
        long age = now - sceneAt;
        int accent = won ? GOLD : RED;
        float phase = progress(age, 305);
        float enter = ease(phase);
        canvas.drawColor(color(0xff071915, .86f * enter));
        float y = Math.max(26, (height - 464) / 2) + 44 * (1 - enter);
        int layer = canvas.saveLayerAlpha(0, 0, WIDTH, height, (int)(enter * 255));
        round(canvas, 18, y, 382, y + 464, 28, 0xff1b3530);
        outline(canvas, 18, y, 382, y + 464, 28, color(accent, .34f), 1);
        halo(canvas, WIDTH / 2, y + 103, 137, color(accent, .25f));
        if (won) confetti(canvas, WIDTH / 2, y + 100, age);
        float medal = ease(progress(age - 40, 340));
        badge(canvas, WIDTH / 2, y + 100 + 12 * (1 - medal), 35 + 27 * medal, accent, !won);
        float title = ease(progress(age - 110, 230));
        text(canvas, won ? "排雷成功" : "踩到地雷", WIDTH / 2, y + 210 + 9 * (1 - title),
                35, color(CREAM, title), true, true);
        resultStat(canvas, 34, y + 240, "用时",
                leaderboardStatus == null ? timeText(elapsed(now)) : rankedTime(elapsed(now)), accent, age - 240);
        resultStat(canvas, 146, y + 240, "已翻开", field.opened + "/" + (field.cells.length - field.mines), accent, age - 330);
        resultStat(canvas, 258, y + 240, LEVELS[field.preset], field.columns + "×" + field.rows, accent, age - 420);
        if (leaderboardStatus != null) {
            text(canvas, leaderboardStatus, WIDTH / 2, y + 327, 12, QUIET, false, true);
        }
        boolean actionsEnabled = interactive && phase == 1;
        button(canvas, 35, y + 337, 365, y + 388, "再来一局", true, actionsEnabled,
                () -> startBoard(field.columns, field.rows, field.mines, field.preset, field.noGuess, field.mineRate));
        button(canvas, 35, y + 400, 365, y + 443, "查看棋盘", false, actionsEnabled, this::showBoard);
        canvas.restoreToCount(layer);
    }

    private void resultStat(Canvas canvas, float x, float y, String label, String value, int accent, long age) {
        float amount = ease(progress(age, 190));
        y += (1 - amount) * 11;
        round(canvas, x, y, x + 108, y + 72, 12, color(0xff2b4a40, amount));
        text(canvas, label, x + 54, y + 25, 13, color(QUIET, amount), false, true);
        text(canvas, value, x + 54, y + 53, value.length() > 9 ? 15 : 20, color(accent, amount), true, true);
    }

    private void badge(Canvas canvas, float x, float y, float size, int accent, boolean loss) {
        circle(canvas, x, y, size, color(accent, .14f));
        ring(canvas, x, y, size - 5, color(accent, .56f), 1.15f);
        circle(canvas, x, y, size - 13, color(accent, .18f));
        ring(canvas, x, y, size - 13, color(accent, .32f), .85f);
        if (loss) mine(canvas, x, y, size * .78f, accent);
        else flag(canvas, x, y, size * 1.05f, accent);
    }

    private void confetti(Canvas canvas, float x, float y, long age) {
        float t = progress(age - 130, 875);
        if (t <= 0 || t >= 1) return;
        int[] palette = { GOLD, MINT, BLUE, RED, CREAM };
        for (int i = 0; i < 38; i++) {
            double angle = i * 2 * Math.PI / 38;
            float reach = (75 + i * 31 % 125) * ease(t);
            float px = x + (float)Math.cos(angle) * reach, py = y + (float)Math.sin(angle) * reach + t * t * 61;
            if (px < 24 || px > 376 || py < y - 80 || py > y + 85) continue;
            canvas.save();
            canvas.rotate(i * 29 + t * 250, px, py);
            round(canvas, px - 2, py - 3, px + 2, py + 3, 1, color(palette[i % palette.length], 1 - t));
            canvas.restore();
        }
    }

    private void sparks(Canvas canvas, float x, float y, float size, long age) {
        float t = progress(age - 40, 520);
        if (t <= 0) return;
        float radius = size * (.25f + 1.72f * ease(t));
        halo(canvas, x, y, size * (.62f + .56f * t), color(GOLD, .72f * Math.max(0, 1 - t * 3)));
        ring(canvas, x, y, radius, color(RED, .85f * (1 - t) * (1 - t)), 1.8f);
        for (int i = 0; i < 17; i++) {
            double angle = i * 2 * Math.PI / 17;
            float dx = (float)Math.cos(angle), dy = (float)Math.sin(angle);
            float travel = radius * (.59f + (i * 5 % 17) * .033f);
            float px = x + dx * travel, py = y + dy * travel + size * t * t * .34f;
            int tint = color(i % 3 == 0 ? RED : GOLD, 1 - t);
            line(canvas, px - dx * size * .2f * (1 - t), py - dy * size * .2f * (1 - t), px, py, tint, 2);
            circle(canvas, px, py, Math.max(1, size * .042f), tint);
        }
    }

    private void flag(Canvas canvas, float x, float y, float size, int shade) {
        line(canvas, x - .22f * size, y - .34f * size, x - .22f * size, y + .33f * size, shade, .07f * size);
        line(canvas, x - .38f * size, y + .33f * size, x + .12f * size, y + .33f * size, shade, .07f * size);
        path.reset();
        path.moveTo(x - .20f * size, y - .34f * size);
        path.lineTo(x + .37f * size, y - .15f * size);
        path.lineTo(x - .20f * size, y + .035f * size);
        path.close();
        paint.setColor(shade);
        canvas.drawPath(path, paint);
    }

    private void mine(Canvas canvas, float x, float y, float size, int shade) {
        circle(canvas, x, y, size * .32f, shade);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            line(canvas, x, y, x + (float)Math.cos(angle) * size * .49f,
                    y + (float)Math.sin(angle) * size * .49f, shade, size * .09f);
        }
        circle(canvas, x - .10f * size, y - .10f * size, size * .06f, color(CREAM, Color.alpha(shade) / 255f));
    }

    private void halo(Canvas canvas, float x, float y, float radius, int tint) {
        paint.setShader(new RadialGradient(x, y, radius, tint, color(tint, 0), Shader.TileMode.CLAMP));
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, radius, paint);
        paint.setShader(null);
    }

    private void round(Canvas canvas, float l, float t, float r, float b, float radius, int shade) {
        paint.setColor(shade);
        canvas.drawRoundRect(l, t, r, b, radius, radius, paint);
    }

    private void outline(Canvas canvas, float l, float t, float r, float b, float radius, int shade, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        round(canvas, l, t, r, b, radius, shade);
        paint.setStyle(Paint.Style.FILL);
    }

    private void circle(Canvas canvas, float x, float y, float radius, int shade) {
        paint.setColor(shade);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void ring(Canvas canvas, float x, float y, float radius, int shade, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        circle(canvas, x, y, radius, shade);
        paint.setStyle(Paint.Style.FILL);
    }

    private void line(Canvas canvas, float x, float y, float ex, float ey, int shade, float width) {
        paint.setColor(shade);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x, y, ex, ey, paint);
    }

    private void text(Canvas canvas, String label, float x, float baseline, float size, int shade,
                      boolean bold, boolean center) {
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        paint.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        paint.setTextSize(size);
        paint.setColor(shade);
        canvas.drawText(label, x, baseline, paint);
    }

    private static int color(int shade, float alpha) {
        return (shade & 0x00ffffff) | (Math.round(255 * clamp(alpha, 0, 1)) << 24);
    }

    private static int mix(int a, int b, float ratio) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * ratio),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * ratio),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * ratio));
    }

    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static float progress(float time, float duration) { return clamp(time / duration, 0, 1); }
    private static float ease(float phase) { float remain = 1 - phase; return 1 - remain * remain * remain; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = toBoardX(event.getX()), y = event.getY() / density;
        if (action == MotionEvent.ACTION_DOWN) {
            if (mixingInput) return true;
            downX = x;
            downY = y;
            armedHit = hitAt(x, y);
            boardGesture = scene == Scene.BOARD && armedHit == null && gameArea.contains(x, y);
            dragging = false;
            if (boardGesture) {
                fitAt = 0;
                dragLeft = left;
                dragTop = top;
                pressed = cellAt(x, y);
                if (pressed >= 0) {
                    pressedAt = SystemClock.uptimeMillis();
                    moveArmed = generation == null && !field.isOver();
                    if (moveArmed && field.canFlag(pressed)) {
                        long elapsed = pressedAt - event.getDownTime();
                        postDelayed(hold, Math.max(0, FLAG_HOLD_MILLIS - elapsed));
                    }
                    activeUntil = Math.max(activeUntil, pressedAt + 80);
                }
                pinch.onTouchEvent(event);
            }
            invalidate();
            return true;
        }
        if (boardGesture) pinch.onTouchEvent(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            armedHit = null;
            stopPress();
            dragging = true;
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP && boardGesture) {
            int remaining = event.getActionIndex() == 0 ? 1 : 0;
            downX = toBoardX(event.getX(remaining));
            downY = event.getY(remaining) / density;
            dragLeft = left;
            dragTop = top;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (pinch.isInProgress() || event.getPointerCount() != 1) return true;
            float dx = x - downX, dy = y - downY;
            if (dx * dx + dy * dy > 49) {
                armedHit = null;
                dragging = true;
                stopPress();
            } else if (pressed >= 0 && cellAt(x, y) != pressed) stopPress();
            if (boardGesture && dragging) {
                left = dragLeft + dx;
                top = dragTop + dy;
                constrainPan();
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            Hit widget = armedHit;
            int cell = pressed;
            boolean flag = flagTool;
            boolean move = moveArmed;
            armedHit = null;
            boolean stationary = !dragging && (x - downX) * (x - downX) + (y - downY) * (y - downY) <= 49;
            stopPress();
            boardGesture = false;
            if (stationary && widget != null && widget.bounds.contains(x, y)) {
                performClick();
                widget.action.run();
            } else if (stationary && cell >= 0 && scene == Scene.BOARD && cellAt(x, y) == cell) {
                performClick();
                if (move) applyMove(cell, flag, null);
            } else if (stationary && scene == Scene.SPLASH) transition(openingScene());
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            boardGesture = false;
            armedHit = null;
            stopPress();
            invalidate();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private float toBoardX(float screenX) { return (screenX - viewportLeft) / density; }

    private Hit hitAt(float x, float y) {
        for (int i = hits.size() - 1; i >= 0; i--)
            if (hits.get(i).bounds.contains(x, y)) return hits.get(i);
        return null;
    }

    private int cellAt(float x, float y) {
        if (field == null || !gameArea.contains(x, y)) return -1;
        float size = unit * zoom;
        int col = (int)Math.floor((x - left) / size);
        int row = (int)Math.floor((y - top) / size);
        return col >= 0 && col < field.columns && row >= 0 && row < field.rows ? row * field.columns + col : -1;
    }

    private boolean canUseTool(int cell) {
        return flagTool ? field.canFlag(cell) : field.canReveal(cell);
    }

    private void longPress() {
        if (!moveArmed || dragging || scene != Scene.BOARD || generation != null || !field.canFlag(pressed)) return;
        int cell = pressed;
        stopPress();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        applyMove(cell, true, null);
    }

    private void stopPress() {
        removeCallbacks(hold);
        if (pressed >= 0) {
            rebounding = pressed;
            releasedAt = SystemClock.uptimeMillis();
            activeUntil = Math.max(activeUntil, releasedAt + 120);
        }
        pressed = -1;
        moveArmed = false;
    }

    private void chooseTool(boolean flag) {
        flagTool = flag;
        invalidate();
    }

    private void fitImmediately() {
        if (field == null || gameArea.width() <= 0) return;
        unit = Math.min((gameArea.width() - 14) / field.columns, (gameArea.height() - 14) / field.rows);
        zoom = largeBoard() ? Math.max(1, DETAIL_CELL_SIZE / unit) : 1;
        left = gameArea.centerX() - unit * zoom * field.columns / 2;
        top = gameArea.centerY() - unit * zoom * field.rows / 2;
        fitAt = 0;
    }

    private boolean largeBoard() { return field.columns > 100 || field.rows > 100; }

    private float fitLeft() { return gameArea.centerX() - unit * field.columns / 2; }
    private float fitTop() { return gameArea.centerY() - unit * field.rows / 2; }

    private void startFit() {
        fitAt = SystemClock.uptimeMillis();
        fitFromZoom = zoom;
        fitFromX = left;
        fitFromY = top;
        invalidate();
    }

    private void advanceFit(long now) {
        float t = ease(progress(now - fitAt, 230));
        zoom = fitFromZoom + (1 - fitFromZoom) * t;
        left = fitFromX + (fitLeft() - fitFromX) * t;
        top = fitFromY + (fitTop() - fitFromY) * t;
        if (t >= 1) fitAt = 0;
    }

    private void constrainPan() {
        left = clamp(left, gameArea.left + 18 - unit * zoom * field.columns, gameArea.right - 18);
        top = clamp(top, gameArea.top + 18 - unit * zoom * field.rows, gameArea.bottom - 18);
    }

    private void selectLevel(int level) {
        if (level == 3) editDifficulty();
        else {
            selection = level;
            invalidate();
        }
    }

    private void chooseMode(boolean value) {
        noGuess = value;
        invalidate();
    }

    private int[] configuration() {
        return selection == 3 ? new int[] { customWidth, customHeight, customMines } : PRESETS[selection];
    }

    static String estimatedDifficulty(int cells, int mines) {
        int scaledMines = mines * 100;
        if (scaledMines < cells * 14) return LEVELS[0];
        if (scaledMines < cells * 18) return LEVELS[1];
        return LEVELS[2];
    }

    private void editDifficulty() {
        if (!gameplayAllowed) return;
        int rate = customMineRate != 0 ? customMineRate
                : Minefield.rateForMines(customWidth, customHeight, customMines);
        dismissGameDialog();
        gameDialog = CustomBoardDialog.show(activity, customWidth, customHeight, rate, noGuess, (columns, rows, percent) -> {
            customWidth = columns;
            customHeight = rows;
            customMineRate = percent;
            customMines = Minefield.minesForRate(columns, rows, percent);
            selection = 3;
            invalidate();
        });
    }

    private void newFromMenu() {
        if (!gameplayAllowed) return;
        Runnable start = () -> {
            int[] config = configuration();
            startBoard(config[0], config[1], config[2], selection, noGuess, selection == 3 ? customMineRate : 0);
        };
        if (field != null && !field.isOver() && (field.state == Minefield.PLAYING || field.flags > 0)) {
            dismissGameDialog();
            gameDialog = new GameDialog(activity, "开始新游戏");
            gameDialog.addContent(GameDialog.text(activity, "开始新游戏会替换上局进度。", 15, CREAM, false), 12);
            gameDialog.show("开始", start);
        } else start.run();
    }

    private void dismissGameDialog() {
        if (gameDialog == null) return;
        gameDialog.dismiss();
        gameDialog = null;
    }

    private void startBoard(int columns, int rows, int mines, int preset, boolean verified, int mineRate) {
        if (!gameplayAllowed) return;
        cancelGeneration();
        stopPress();
        field = new Minefield(columns, rows, mines, preset, verified, mineRate);
        boardOwner = isStandardBoard() ? leaderboardAccount : null;
        leaderboardStatus = null;
        prepareBoard();
        accumulated = started = 0;
        flagTool = false;
        entranceAt = largeBoard() ? 0 : SystemClock.uptimeMillis();
        activeUntil = entranceAt == 0 ? 0 : entranceAt + 535;
        boolean retry = scene == Scene.RESULT;
        transition(Scene.BOARD);
        if (retry) {
            boardFadeIn = true;
            mixingInput = true;
        }
        save();
    }

    private void prepareBoard() {
        int animatedCells = largeBoard() ? 0 : field.cells.length;
        reveals = new long[animatedCells];
        flags = new long[animatedCells];
        rebounding = -1;
        resultAt = explosionAt = entranceAt = 0;
        generationNote = null;
        overview = null;
        overviewRow = null;
        entryRings = new byte[animatedCells];
        if (animatedCells != 0) {
            float centerX = (field.columns - 1) / 2f, centerY = (field.rows - 1) / 2f;
            entryDelay = Math.min(52, 365f / Math.max(1, (float)Math.hypot(centerX, centerY)));
            for (int y = 0; y < field.rows; y++)
                for (int x = 0; x < field.columns; x++)
                    entryRings[y * field.columns + x] = (byte)Math.hypot(x - centerX, y - centerY);
        }
        fitImmediately();
    }

    private void showBoard() {
        if (!gameplayAllowed) return;
        if (field == null) return;
        if (boardOwner != null && !boardOwner.equals(leaderboardAccount)) clearRankedBoard();
        if (started == 0 && running && field.state == Minefield.PLAYING) started = SystemClock.uptimeMillis();
        transition(Scene.BOARD);
    }

    private void showHome() {
        cancelGeneration();
        pauseClock();
        resultAt = 0;
        transition(Scene.HOME);
        save();
    }

    private void transition(Scene next) {
        if (!gameplayAllowed && next != Scene.ENTRY) return;
        if (scene == next) return;
        boolean togglesResult = (scene == Scene.BOARD && next == Scene.RESULT) ||
                (scene == Scene.RESULT && next == Scene.BOARD);
        stopPress();
        armedHit = null;
        hits.clear();
        boardGesture = false;
        previous = togglesResult ? null : scene;
        boardFadeIn = false;
        scene = next;
        sceneAt = SystemClock.uptimeMillis();
        mixingInput = !togglesResult;
        setContentDescription(appName + (next == Scene.ENTRY ? "，选择游戏入口" :
                next == Scene.HOME ? "，选择模式和难度" : next == Scene.RESULT ? "结算" : "棋盘"));
        invalidate();
    }

    private void applyMove(int cell, boolean flag, byte[] verifiedLayout) {
        if (!gameplayAllowed || scene != Scene.BOARD || field.isOver() || generation != null) return;
        if (!flag && field.noGuess && field.state == Minefield.READY && verifiedLayout == null) {
            if (field.canReveal(cell)) generate(cell);
            return;
        }
        changed.clear();
        boolean ready = field.state == Minefield.READY;
        if (!(flag ? field.flag(cell, changed) : field.reveal(cell, random, verifiedLayout, changed))) return;
        long now = SystemClock.uptimeMillis();
        if (ready && field.state == Minefield.PLAYING) started = now;
        if (flag) {
            if (flags.length != 0) {
                for (int changedCell : changed) flags[changedCell] = now;
                activeUntil = Math.max(activeUntil, now + 240);
            }
        } else {
            long end = reveals.length == 0 ? now : animateReveal(cell, now);
            activeUntil = Math.max(activeUntil, end);
            if (field.isOver()) {
                pauseClock();
                if (field.state == Minefield.LOST) {
                    explosionAt = now;
                    end = Math.max(end, now + 560);
                }
                resultAt = Math.max(end, now + 145);
                activeUntil = Math.max(activeUntil, resultAt);
            }
        }
        updateOverview();
        changed.clear();
        save();
        if (field.state == Minefield.WON && boardOwner != null && boardOwner.equals(leaderboardAccount)
                && rankedWin != null && isStandardBoard()) {
            Minefield completed = field;
            leaderboardStatus = "正在提交成绩…";
            rankedWin.submit(completed.noGuess, completed.preset, Math.max(1, accumulated), status -> {
                if (field != completed || !gameplayAllowed || boardOwner == null
                        || !boardOwner.equals(leaderboardAccount)) return;
                leaderboardStatus = status;
                invalidate();
            });
        }
        invalidate();
    }

    private long animateReveal(int cell, long now) {
        float maxDistance = 0;
        boolean wave = changed.size() >= 8;
        if (wave)
            for (int index : changed) maxDistance = Math.max(maxDistance, distance(cell, index));
        float rate = wave ? Math.min(32, 335 / Math.max(1, maxDistance)) : 0;
        long end = now;
        for (int index : changed) {
            reveals[index] = now + (long)(rate == 0 ? 0 : distance(cell, index) * rate);
            flags[index] = 0;
            end = Math.max(end, reveals[index] + 110);
        }
        return end;
    }

    private float distance(int from, int to) {
        return (float)Math.hypot(from % field.columns - to % field.columns, from / field.columns - to / field.columns);
    }

    private void generate(int cell) {
        Minefield request = field.copyOpening(false);
        long version = ++generationVersion;
        long seed = random.nextLong();
        generationNote = null;
        generation = worker.submit(() -> {
            byte[] result = NoGuess.generate(request, cell, seed);
            post(() -> {
                if (version != generationVersion) return;
                generation = null;
                if (result == null) generationNote = "未生成无猜棋盘，点格重试";
                else applyMove(cell, false, result);
                invalidate();
            });
        });
        invalidate();
    }

    private void cancelGeneration() {
        generationVersion++;
        if (generation != null) generation.cancel(true);
        generation = null;
        generationNote = null;
    }

    boolean back() {
        if (scene == Scene.ENTRY) return false;
        if (scene == Scene.HOME) {
            dismissGameDialog();
            gameDialog = new GameDialog(activity, "退出游戏");
            gameDialog.addContent(GameDialog.text(activity, "确定退出游戏吗？", 15, CREAM, false), 12);
            gameDialog.show("退出", activity::finish);
        } else if (scene == Scene.SPLASH) transition(openingScene());
        else if (scene == Scene.RESULT) showBoard();
        else showHome();
        return true;
    }

    void resume() {
        running = true;
        if (scene == Scene.BOARD && field != null && field.state == Minefield.PLAYING && started == 0)
            started = SystemClock.uptimeMillis();
        invalidate();
    }

    void pause() {
        running = false;
        removeCallbacks(clockTick);
        stopPress();
        cancelGeneration();
        pauseClock();
        save();
    }

    void shutdown() {
        dismissGameDialog();
        cancelGeneration();
        worker.shutdownNow();
    }

    private long elapsed(long now) { return accumulated + (started == 0 ? 0 : now - started); }

    private void pauseClock() {
        if (started == 0) return;
        accumulated = elapsed(SystemClock.uptimeMillis());
        started = 0;
    }

    private static String timeText(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds %= 60;
        return (minutes < 10 ? "0" : "") + minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    static String rankedTime(long milliseconds) {
        long time = Math.max(1, milliseconds);
        return timeText(time) + "." + Long.toString(1000 + time % 1000).substring(1);
    }

    private boolean isStandardBoard() {
        if (field.preset < 0 || field.preset >= PRESETS.length) return false;
        int[] preset = PRESETS[field.preset];
        return field.columns == preset[0] && field.rows == preset[1] && field.mines == preset[2];
    }

    private void save() {
        if (field == null) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + field.cells.length * 2);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(SAVE_VERSION);
                output.writeLong(elapsed(SystemClock.uptimeMillis()));
                field.write(output);
            }
            preferences.edit().putString("board", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    .putString("leaderboard_owner", field.isOver() ? null : boardOwner).apply();
        } catch (IOException error) {
            android.util.Log.w("MineSweeper", "Failed to save board", error);
        }
    }

    private void restore() {
        String stored = preferences.getString("board", null);
        if (stored == null) return;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Base64.decode(stored, Base64.DEFAULT)))) {
            if (input.readInt() != SAVE_VERSION) return;
            long savedTime = input.readLong();
            field = Minefield.read(input);
            if (!field.isOver() && isStandardBoard()) {
                boardOwner = preferences.getString("leaderboard_owner", null);
            }
            accumulated = Math.max(0, savedTime);
            prepareBoard();
        } catch (IOException | IllegalArgumentException error) {
            android.util.Log.w("MineSweeper", "Failed to load board", error);
        }
    }

    private static final class Hit {
        final RectF bounds;
        final String label;
        final Runnable action;
        Hit(RectF bounds, String label, Runnable action) { this.bounds = bounds; this.label = label; this.action = action; }
    }
}
