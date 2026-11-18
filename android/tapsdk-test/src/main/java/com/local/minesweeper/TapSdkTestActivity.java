package com.local.minesweeper;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tapsdk.antiaddiction.Config;
import com.tapsdk.antiaddictionui.AntiAddictionUIKit;
import com.taptap.sdk.AccessToken;
import com.taptap.sdk.AccountGlobalError;
import com.taptap.sdk.LoginSdkConfig;
import com.taptap.sdk.Profile;
import com.taptap.sdk.RegionType;
import com.taptap.sdk.TapLoginHelper;
import com.taptap.sdk.net.Api.ApiCallback;
import com.tds.common.TapCommon;

import java.lang.ref.WeakReference;

public final class TapSdkTestActivity extends MainActivity {
    private static final String TAG = "MineSweeperTapSDK";
    private static final int POLICY_VERSION = 5;
    private static final Uri POLICY_URL = Uri.parse(BuildConfig.TAPTAP_PRIVACY_POLICY_URL);
    private static final String ACCEPTED_VERSION = "accepted_policy_version";
    private static final String LOGOUT_PENDING = "logout_pending";
    private static final int LOGIN_SUCCESS = 500;
    private static final int EXITED = 1000;
    private static final int SWITCH_ACCOUNT = 1001;
    private static final int PERIOD_RESTRICT = 1030;
    private static final int DURATION_LIMIT = 1050;
    private static final int AGE_LIMIT = 1100;
    private static final int INVALID_CLIENT_OR_NETWORK_ERROR = 1200;
    private static final int REAL_NAME_STOP = 9002;
    private static boolean sdkInitialized;

    private SharedPreferences privacy;
    private AlertDialog dialog;
    private TapLeaderboard leaderboard;
    private TapLeaderboardDialog leaderboardDialog;
    private boolean loginInProgress;
    private boolean profileLogin;
    private boolean refreshProfile;
    private boolean profileInProgress;
    private boolean complianceInProgress;
    private boolean playable;
    private boolean resumed;
    private boolean stopped;
    private boolean checkOnResume = true;
    private int accountSession;
    private int reopenLeaderboard = -1;
    private String complianceOpenId;

    private final TapLoginHelper.TapLoginResultCallback loginCallback =
            new TapLoginHelper.TapLoginResultCallback() {
        @Override public void onLoginSuccess(AccessToken token) {
            gameView().post(() -> completeLogin(token != null, token == null));
        }

        @Override public void onLoginCancel() {
            runOnUiThread(() -> completeLogin(false, false));
        }

        @Override public void onLoginError(AccountGlobalError error) {
            runOnUiThread(() -> completeLogin(false, true));
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        privacy = getSharedPreferences("tap_privacy", MODE_PRIVATE);
        gameView().setTapTapActions(BitmapFactory.decodeResource(getResources(), R.drawable.taptap_login),
                this::openTapTap, this::showPrivacyPolicy);
        if (TapLeaderboard.configured()) {
            leaderboard = TapLeaderboard.get(this);
            gameView().setLeaderboardActions(() -> showLeaderboard(gameView().selectedLeaderboard()),
                    leaderboard::submit);
        }
        lockGameplay(R.string.tap_compliance_required);
    }

    @Override protected void onResume() {
        super.onResume();
        stopped = false;
        resumed = true;
        if (playable) allowGameplay();
        if (checkOnResume) {
            checkOnResume = false;
            if (hasConsent()) enterOrLogin(false);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onStop() {
        stopped = true;
        super.onStop();
        if (playable) {
            lockGameplay(R.string.tap_compliance_checking);
            checkOnResume = true;
        }
    }

    private void openTapTap() {
        if (hasOpenDialog()) return;
        if (!isConfigured()) {
            Toast.makeText(this, R.string.tap_login_unconfigured, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasConsent()) {
            showConsent();
        } else if (busy()) {
            Toast.makeText(this, R.string.tap_compliance_checking, Toast.LENGTH_SHORT).show();
        } else if (sdkInitialized && !privacy.getBoolean(LOGOUT_PENDING, false)
                && TapLoginHelper.getCurrentAccessToken() != null) {
            showAccount();
        } else {
            enterOrLogin(true);
        }
    }

    private void showConsent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.tap_privacy_explanation);
        content.addView(explanation);

        Button policy = new Button(this);
        policy.setText(R.string.tap_privacy_open);
        policy.setOnClickListener(view -> openPolicy());
        content.addView(policy);

        CheckBox agreement = new CheckBox(this);
        agreement.setText(R.string.tap_privacy_accept);
        content.addView(agreement);

        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.tap_login_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tap_login_continue, (prompt, which) -> {
                    if (!agreement.isChecked()) return;
                    privacy.edit().putInt(ACCEPTED_VERSION, POLICY_VERSION).apply();
                    enterOrLogin(true);
                })
                .show();
        Button continueButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        continueButton.setEnabled(false);
        agreement.setOnCheckedChangeListener((button, checked) -> continueButton.setEnabled(checked));
    }

    private void enterOrLogin(boolean interactive) {
        if (!hasConsent() || !isConfigured() || busy() || isFinishing() || isDestroyed()) return;
        try {
            if (!sdkInitialized) {
                TapCommon.setDurationStatisticsEnabled(false);
                TapLoginHelper.init(getApplicationContext(), BuildConfig.TAPTAP_CLIENT_ID,
                        new LoginSdkConfig(true, true, RegionType.CN));
                sdkInitialized = true;
            }
            if (!AntiAddictionUIKit.isInitialized()) {
                Config config = new Config.Builder()
                        .withClientId(BuildConfig.TAPTAP_CLIENT_ID)
                        .enableTapLogin(true)
                        .showSwitchAccount(true)
                        .useAgeRange(false)
                        .build();
                AntiAddictionUIKit.init(this, config);
            }
            if (privacy.getBoolean(LOGOUT_PENDING, false) && !logout()) return;
            boolean signedIn = TapLoginHelper.getCurrentAccessToken() != null;
            gameView().setTapTapSignedIn(signedIn);
            if (signedIn) {
                lockGameplay(R.string.tap_compliance_checking);
                checkCurrentProfile();
            } else {
                lockGameplay(R.string.tap_compliance_required);
                if (interactive) {
                    loginInProgress = true;
                    TapLoginHelper.registerLoginCallback(loginCallback);
                    TapLoginHelper.startTapLogin(this, TapLoginHelper.SCOPE_BASIC_INFO);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            Log.w(TAG, "TapTap login could not start: " + failure.getClass().getSimpleName());
            clearLoginCallback();
            profileInProgress = false;
            lockGameplay(R.string.tap_login_failed);
        }
    }

    private void completeLogin(boolean signedIn, boolean failed) {
        if (!loginInProgress || !hasConsent() || isFinishing() || isDestroyed()) return;
        boolean wasProfileLogin = profileLogin;
        clearLoginCallback();
        if (wasProfileLogin) {
            if (failed) Toast.makeText(this, R.string.tap_login_failed, Toast.LENGTH_SHORT).show();
            refreshProfile = signedIn;
            if (TapLoginHelper.getCurrentAccessToken() == null) reopenLeaderboard = -1;
            checkOnResume = !resumed;
            enterOrLogin(false);
            return;
        }
        gameView().setTapTapSignedIn(signedIn);
        if (signedIn) {
            enterOrLogin(false);
        } else if (failed) {
            lockGameplay(R.string.tap_login_failed);
        } else {
            lockGameplay(R.string.tap_compliance_required);
        }
    }

    private void checkCurrentProfile() {
        String openId = openId(TapLoginHelper.getCurrentProfile());
        if (!refreshProfile && openId != null) {
            startCompliance(openId);
            return;
        }
        refreshProfile = false;
        int session = accountSession;
        profileInProgress = true;
        TapLoginHelper.fetchProfileForCurrentAccessToken(new ApiCallback<Profile>() {
            @Override public void onSuccess(Profile profile) {
                runOnUiThread(() -> finishProfileCheck(session, profile));
            }

            @Override public void onError(Throwable failure) {
                runOnUiThread(() -> finishProfileCheck(session, null));
            }
        });
    }

    private void finishProfileCheck(int session, Profile profile) {
        if (session != accountSession || !hasConsent() || isFinishing() || isDestroyed()) return;
        profileInProgress = false;
        String openId = openId(profile);
        if (openId != null && TapLoginHelper.getCurrentAccessToken() != null) {
            startCompliance(openId);
        } else {
            lockGameplay(R.string.tap_compliance_error);
        }
    }

    private void startCompliance(String openId) {
        if (!resumed) {
            checkOnResume = true;
            return;
        }
        if (complianceInProgress) return;
        complianceOpenId = openId;
        complianceInProgress = true;
        try {
            WeakReference<TapSdkTestActivity> activity = new WeakReference<>(this);
            AntiAddictionUIKit.setAntiAddictionCallback((code, extras) -> {
                TapSdkTestActivity current = activity.get();
                if (current != null) current.runOnUiThread(() -> current.onComplianceResult(code));
            });
            AntiAddictionUIKit.startupWithTapTap(this, openId);
        } catch (RuntimeException | LinkageError failure) {
            complianceInProgress = false;
            Log.w(TAG, "TapTap compliance could not start: " + failure.getClass().getSimpleName());
            lockGameplay(R.string.tap_compliance_error);
        }
    }

    private void onComplianceResult(int code) {
        if (isFinishing() || isDestroyed()) return;
        if (complianceOpenId == null) {
            if (code != EXITED) {
                complianceInProgress = false;
                if (code == LOGIN_SUCCESS) AntiAddictionUIKit.exit();
            }
            return;
        }
        boolean checking = complianceInProgress;
        switch (code) {
            case LOGIN_SUCCESS:
                complianceInProgress = false;
                if (checking && hasConsent() && TapLoginHelper.getCurrentAccessToken() != null
                        && complianceOpenId.equals(openId(TapLoginHelper.getCurrentProfile()))) {
                    gameView().setTapTapSignedIn(true);
                    if (stopped) {
                        checkOnResume = true;
                    } else {
                        playable = true;
                        if (resumed) allowGameplay();
                    }
                } else if (checking) {
                    lockGameplay(R.string.tap_compliance_error);
                }
                break;
            case EXITED:
            case SWITCH_ACCOUNT:
                complianceInProgress = false;
                logout();
                break;
            case PERIOD_RESTRICT:
                showRestriction(R.string.tap_compliance_period);
                break;
            case DURATION_LIMIT:
                showRestriction(R.string.tap_compliance_duration);
                break;
            case AGE_LIMIT:
                showRestriction(R.string.tap_compliance_age);
                break;
            case INVALID_CLIENT_OR_NETWORK_ERROR:
                showRestriction(R.string.tap_compliance_error);
                break;
            case REAL_NAME_STOP:
                showRestriction(R.string.tap_compliance_cancelled);
                break;
            default:
                Log.d(TAG, "TapTap compliance event " + code);
        }
    }

    private void showRestriction(int message) {
        complianceInProgress = false;
        checkOnResume = false;
        reopenLeaderboard = -1;
        if (dialog != null) dialog.dismiss();
        lockGameplay(message);
    }

    private void lockGameplay(int message) {
        playable = false;
        closeLeaderboard();
        if (leaderboard != null) leaderboard.suspend();
        gameView().setLeaderboardAccount(null);
        gameView().lockGameplay(getString(message));
    }

    private void allowGameplay() {
        if (leaderboard != null && hasConsent()) {
            AccessToken token = TapLoginHelper.getCurrentAccessToken();
            Profile profile = TapLoginHelper.getCurrentProfile();
            if (token != null && complianceOpenId != null && complianceOpenId.equals(openId(profile))) {
                gameView().setLeaderboardAccount(leaderboard.allow(complianceOpenId, token, profile));
            }
        }
        gameView().allowGameplay();
        if (reopenLeaderboard >= 0 && !loginInProgress) {
            int board = reopenLeaderboard;
            reopenLeaderboard = -1;
            showLeaderboard(board);
        }
    }

    private void showLeaderboard(int board) {
        if (leaderboard == null || !playable || !resumed || !hasConsent() || hasOpenDialog()
                || (leaderboardDialog != null && leaderboardDialog.isShowing())) return;
        boolean hasPublicProfile = TapLeaderboard.hasPublicProfile(TapLoginHelper.getCurrentAccessToken());
        leaderboardDialog = new TapLeaderboardDialog(this, leaderboard, board,
                hasPublicProfile ? null : this::confirmProfileAuthorization);
        leaderboardDialog.show();
    }

    private void confirmProfileAuthorization() {
        if (!playable || !resumed || busy() || hasOpenDialog() || leaderboardDialog == null) return;
        int board = leaderboardDialog.selectedBoard();
        dialog = new AlertDialog.Builder(this)
                .setTitle("授权 TapTap 公开资料")
                .setMessage("排行榜会向其他玩家展示你的 TapTap 昵称和头像。跳过授权仍可上榜，将使用匿名昵称和默认头像。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("继续授权", (prompt, which) -> authorizeProfile(board))
                .show();
    }

    private void authorizeProfile(int board) {
        if (!playable || !resumed || !hasConsent() || busy()) return;
        reopenLeaderboard = board;
        lockGameplay(R.string.tap_compliance_checking);
        try {
            profileLogin = true;
            loginInProgress = true;
            TapLoginHelper.registerLoginCallback(loginCallback);
            TapLoginHelper.startTapLogin(this, TapLoginHelper.SCOPE_PUBLIC_PROFILE);
        } catch (RuntimeException | LinkageError failure) {
            completeLogin(false, true);
        }
    }

    private void closeLeaderboard() {
        if (leaderboardDialog != null) {
            leaderboardDialog.dismiss();
            leaderboardDialog = null;
        }
    }

    private boolean busy() {
        return loginInProgress || profileInProgress || complianceInProgress;
    }

    private boolean hasConsent() {
        return privacy.getInt(ACCEPTED_VERSION, 0) == POLICY_VERSION;
    }

    private static String openId(Profile profile) {
        if (profile == null || profile.getOpenid() == null || profile.getOpenid().isEmpty()) return null;
        return profile.getOpenid();
    }

    private static boolean isConfigured() {
        return !BuildConfig.TAPTAP_CLIENT_ID.isEmpty() && "CN".equals(BuildConfig.TAPTAP_REGION)
                && "https".equals(POLICY_URL.getScheme()) && POLICY_URL.getHost() != null;
    }

    private void clearLoginCallback() {
        if (!loginInProgress) return;
        loginInProgress = false;
        profileLogin = false;
        if (TapLoginHelper.getLoginCallback() == loginCallback) {
            TapLoginHelper.unregisterLoginCallback();
        }
    }

    private void showAccount() {
        AlertDialog.Builder account = new AlertDialog.Builder(this)
                .setTitle(R.string.tap_account_title)
                .setMessage(playable ? R.string.tap_login_success : R.string.tap_compliance_required)
                .setNegativeButton(R.string.tap_logout, (prompt, which) -> {
                    if (!logout()) Toast.makeText(this, R.string.tap_logout_failed, Toast.LENGTH_SHORT).show();
                });
        if (!playable) {
            account.setPositiveButton(R.string.tap_compliance_retry, (prompt, which) -> enterOrLogin(true));
        } else {
            account.setPositiveButton(android.R.string.ok, null);
        }
        dialog = account.show();
    }

    private void showPrivacyPolicy() {
        if (hasOpenDialog()) return;
        if (!hasConsent()) {
            openPolicy();
            return;
        }
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.tap_privacy_title)
                .setMessage(R.string.tap_privacy_saved)
                .setNeutralButton(R.string.tap_privacy_open, (prompt, which) -> openPolicy())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tap_privacy_revoke, (prompt, which) -> revokeConsent())
                .show();
    }

    private void revokeConsent() {
        boolean wasLoggingIn = loginInProgress;
        privacy.edit().remove(ACCEPTED_VERSION).apply();
        if (leaderboard != null) leaderboard.clearPendingScores();
        gameView().clearRankedBoard();
        logout();
        if (wasLoggingIn) privacy.edit().putBoolean(LOGOUT_PENDING, true).apply();
        Toast.makeText(this, R.string.tap_privacy_revoked, Toast.LENGTH_SHORT).show();
    }

    private boolean logout() {
        accountSession++;
        complianceOpenId = null;
        reopenLeaderboard = -1;
        refreshProfile = false;
        profileInProgress = false;
        checkOnResume = false;
        clearLoginCallback();
        if (dialog != null) dialog.dismiss();
        gameView().setTapTapSignedIn(false);
        lockGameplay(R.string.tap_compliance_required);
        boolean cleared = true;
        if (sdkInitialized) {
            try {
                if (AntiAddictionUIKit.isInitialized()) AntiAddictionUIKit.exit();
            } catch (RuntimeException | LinkageError failure) {
                cleared = false;
                Log.w(TAG, "TapTap compliance exit failed: " + failure.getClass().getSimpleName());
            }
            try {
                TapLoginHelper.logout();
            } catch (RuntimeException | LinkageError failure) {
                cleared = false;
                Log.w(TAG, "TapTap logout failed: " + failure.getClass().getSimpleName());
            }
        }
        privacy.edit().putBoolean(LOGOUT_PENDING, !cleared).apply();
        return cleared;
    }

    private void openPolicy() {
        if (!"https".equals(POLICY_URL.getScheme()) || POLICY_URL.getHost() == null) {
            Toast.makeText(this, R.string.tap_privacy_browser_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, POLICY_URL));
        } catch (ActivityNotFoundException failure) {
            Toast.makeText(this, R.string.tap_privacy_browser_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasOpenDialog() {
        return dialog != null && dialog.isShowing();
    }

    @Override protected void onDestroy() {
        clearLoginCallback();
        closeLeaderboard();
        if (leaderboard != null) leaderboard.suspend();
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
}
