package com.local.minesweeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.tapsdk.bootstrap.account.TDSUser;
import com.tapsdk.lc.LCLeaderboard;
import com.tapsdk.lc.LCRanking;
import com.tapsdk.lc.LCUser;
import com.tapsdk.lc.LeanCloud;
import com.taptap.sdk.AccessToken;
import com.taptap.sdk.Profile;
import com.taptap.sdk.TapLoginHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.SerialDisposable;
import io.reactivex.schedulers.Schedulers;

/** Owns the storage SDK's process-wide user session. Call from the main thread. */
final class TapLeaderboard {
    private static final String[] BOARDS = {
            "classic_beginner", "classic_intermediate", "classic_advanced",
            "no_guess_beginner", "no_guess_intermediate", "no_guess_advanced"
    };
    static final String AVATAR_FIELD = "leaderboardAvatar";
    private static final List<String> DISPLAY_FIELDS = Arrays.asList("nickname", AVATAR_FIELD);
    private static final String WAITING = "成绩待同步";
    private static TapLeaderboard instance;

    interface Result { void show(List<LCRanking> top, LCRanking mine, String error); }

    private final Context application;
    private final SharedPreferences pending;
    private final CompositeDisposable requests = new CompositeDisposable();
    private final SerialDisposable rankingRequest = new SerialDisposable();
    private final boolean[] uploading = new boolean[BOARDS.length];
    private final GameView.RankStatus[] winStatus = new GameView.RankStatus[BOARDS.length];
    private boolean initialized;
    private boolean authenticating;
    private boolean syncingProfile;
    private int session;
    private String account;
    private Map<String, Object> credentials;
    private String avatarUrl;
    private TDSUser player;
    private BoardQuery pendingQuery;

    static boolean configured() {
        Uri endpoint = Uri.parse(BuildConfig.TAPTAP_LEADERBOARD_API_URL);
        return !BuildConfig.TAPTAP_LEADERBOARD_CLIENT_TOKEN.isEmpty()
                && "https".equals(endpoint.getScheme()) && endpoint.getHost() != null
                && endpoint.getUserInfo() == null && endpoint.getQuery() == null && endpoint.getFragment() == null;
    }

    static TapLeaderboard get(Context context) {
        if (instance == null) instance = new TapLeaderboard(context.getApplicationContext());
        return instance;
    }

    private TapLeaderboard(Context application) {
        this.application = application;
        pending = application.getSharedPreferences("leaderboard_pending", Context.MODE_PRIVATE);
    }

    String allow(String openId, AccessToken token, Profile profile) {
        String owner = accountKey(openId);
        if (owner.equals(account)) return owner;
        suspend();
        account = owner;
        credentials = new HashMap<>();
        credentials.put("access_token", token.access_token);
        credentials.put("kid", token.kid);
        credentials.put("mac_key", token.mac_key);
        credentials.put("token_type", token.token_type);
        credentials.put("mac_algorithm", token.mac_algorithm);
        credentials.put("openid", openId);
        if (profile.getUnionid() != null) credentials.put("unionid", profile.getUnionid());
        if (hasPublicProfile(token)) {
            String name = profile.getName();
            if (name != null && !name.trim().isEmpty()) credentials.put("name", name.trim());
            avatarUrl = LeaderboardAvatars.supportedUrl(profile.getAvatar());
        }
        authenticate();
        return owner;
    }

    void suspend() {
        if (account == null) return;
        session++;
        account = null;
        credentials = null;
        avatarUrl = null;
        syncingProfile = false;
        player = null;
        pendingQuery = null;
        rankingRequest.set(null);
        requests.clear();
        for (int i = 0; i < BOARDS.length; i++) {
            uploading[i] = false;
            updateStatus(i, WAITING);
            winStatus[i] = null;
        }
        clearStorageUser();
    }

    void clearPendingScores() {
        pending.edit().clear().apply();
    }

    void submit(boolean noGuess, int preset, long time, GameView.RankStatus status) {
        if (account == null || preset < 0 || preset > 2 || time <= 0) {
            status.update("本局未提交");
            return;
        }
        int board = (noGuess ? 3 : 0) + preset;
        String key = pendingKey(board);
        if (time < pending.getLong(key, Long.MAX_VALUE)) pending.edit().putLong(key, time).apply();
        winStatus[board] = status;
        status.update(player == null ? WAITING : "正在提交成绩…");
        if (player == null) authenticate();
        else send(board);
    }

    void load(int board, Result result) {
        if (account == null) {
            result.show(null, null, "排行榜暂不可用");
            return;
        }
        rankingRequest.set(null);
        pendingQuery = new BoardQuery(board, result);
        if (player == null) authenticate();
        else {
            flush();
            read();
        }
    }

    private void authenticate() {
        if (account == null || authenticating || player != null) return;
        int expected = session;
        try {
            if (!initialized) {
                LeanCloud.initialize(application, BuildConfig.TAPTAP_CLIENT_ID,
                        BuildConfig.TAPTAP_LEADERBOARD_CLIENT_TOKEN, BuildConfig.TAPTAP_LEADERBOARD_API_URL);
                LCUser.alwaysUseSubUserClass(TDSUser.class);
                initialized = true;
                clearStorageUser();
            }
            authenticating = true;
            // Complete this request before starting another. The SDK updates its current user during login.
            network(LCUser.loginWithAuthData(TDSUser.class, credentials, "taptap"))
                    .subscribe(user -> finishAuthentication(expected, user), error -> finishAuthentication(expected, null));
        } catch (RuntimeException | LinkageError error) {
            Log.w("MineSweeperRanks", "Storage login unavailable: " + error.getClass().getSimpleName());
            finishAuthentication(expected, null);
        }
    }

    private void finishAuthentication(int expected, TDSUser user) {
        authenticating = false;
        if (expected != session) {
            clearStorageUser();
            authenticate();
            return;
        }
        if (user == null) {
            clearStorageUser();
            if (pendingQuery != null) {
                pendingQuery.result.show(null, null, "连接排行榜失败，请重试");
                pendingQuery = null;
            }
            return;
        }
        player = user;
        syncProfile(expected, user);
        flush();
        read();
    }

    private void syncProfile(int expected, TDSUser user) {
        String nickname = (String) credentials.get("name");
        boolean updateNickname = nickname != null && !nickname.equals(user.getString("nickname"));
        boolean updateAvatar = !Objects.equals(avatarUrl, user.get(AVATAR_FIELD));
        if (!updateNickname && !updateAvatar) return;
        if (updateNickname) user.put("nickname", nickname);
        if (updateAvatar) {
            if (avatarUrl == null) user.remove(AVATAR_FIELD);
            else user.put(AVATAR_FIELD, avatarUrl);
        }
        syncingProfile = true;
        try {
            requests.add(network(user.saveInBackground(user)).subscribe(
                    ignored -> finishProfileSync(expected, true), error -> finishProfileSync(expected, false)));
        } catch (RuntimeException failure) {
            finishProfileSync(expected, false);
        }
    }

    private void finishProfileSync(int expected, boolean saved) {
        if (expected != session) return;
        syncingProfile = false;
        if (!saved) Log.w("MineSweeperRanks", "Could not sync leaderboard profile");
        read();
    }

    private void flush() {
        for (int i = 0; i < BOARDS.length; i++) send(i);
    }

    private void send(int board) {
        String key = pendingKey(board);
        long time = pending.getLong(key, 0);
        if (time == 0 || uploading[board]) return;
        uploading[board] = true;
        updateStatus(board, "正在提交成绩…");
        int expected = session;
        requests.add(network(LCLeaderboard.updateStatistic(player,
                Collections.singletonMap(BOARDS[board], (double) time))).subscribe(
                ignored -> finishUpload(expected, board, key, time, true),
                error -> finishUpload(expected, board, key, time, false)));
    }

    private void finishUpload(int expected, int board, String key, long sent, boolean ok) {
        if (expected != session) return;
        uploading[board] = false;
        long queued = pending.getLong(key, 0);
        if (ok && queued == sent) pending.edit().remove(key).apply();
        if (ok && queued > 0 && queued != sent) {
            send(board);
            return;
        }
        updateStatus(board, ok ? "榜单成绩已同步" : WAITING);
        winStatus[board] = null;
        read();
    }

    private void read() {
        BoardQuery query = pendingQuery;
        if (query == null || syncingProfile || uploading[query.board]) return;
        pendingQuery = null;
        int expected = session;
        String name = BOARDS[query.board];
        LCLeaderboard board = LCLeaderboard.createWithoutData(name);
        TDSUser current = player;
        String playerId = current.getObjectId();
        Observable<List<LCRanking>> top = board.getResults(0, 20, DISPLAY_FIELDS, null)
                .map(response -> response.getResults() == null ? Collections.emptyList() : response.getResults());
        Observable<List<LCRanking>> mine = LCLeaderboard.getUserStatistics(current, Collections.singletonList(name))
                .flatMap(stats -> stats.getResults() == null || stats.getResults().isEmpty()
                        ? Observable.just(Collections.emptyList())
                        : board.getAroundResults(playerId, 0, 1, null, null)
                              .map(response -> response.getResults() == null ? Collections.emptyList() : response.getResults()));
        rankingRequest.set(network(Observable.zip(top, mine, (topRows, myRows) -> {
            LCRanking personal = null;
            for (LCRanking row : myRows) {
                if (row.getUser() != null && playerId.equals(row.getUser().getObjectId())) personal = row;
            }
            return new BoardResults(topRows, personal);
        })).subscribe(rows -> {
            if (expected == session) query.result.show(rows.top, rows.mine, null);
        }, error -> {
            if (expected == session) query.result.show(null, null, "读取排行榜失败，请重试");
        }));
    }

    private void clearStorageUser() {
        if (!initialized) return;
        try {
            LCUser.logOut();
        } catch (RuntimeException failure) {
            Log.w("MineSweeperRanks", "Could not clear ranking session: " + failure.getClass().getSimpleName());
        }
    }

    private void updateStatus(int board, String text) {
        if (winStatus[board] != null) winStatus[board].update(text);
    }

    private String pendingKey(int board) {
        return account + "_" + BOARDS[board];
    }

    private static <T> Observable<T> network(Observable<T> request) {
        return request.firstOrError().timeout(20, TimeUnit.SECONDS).toObservable()
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }

    private static String accountKey(String openId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((BuildConfig.TAPTAP_CLIENT_ID + ":" + openId).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean hasPublicProfile(AccessToken token) {
        return token != null && token.scopeSet != null && token.scopeSet.contains(TapLoginHelper.SCOPE_PUBLIC_PROFILE);
    }

    private static final class BoardQuery {
        final int board;
        final Result result;

        BoardQuery(int board, Result result) {
            this.board = board;
            this.result = result;
        }
    }

    private static final class BoardResults {
        final List<LCRanking> top;
        final LCRanking mine;

        BoardResults(List<LCRanking> top, LCRanking mine) {
            this.top = top;
            this.mine = mine;
        }
    }
}
