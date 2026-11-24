package com.local.minesweeper;

import android.app.Activity;
import android.os.Bundle;

public final class MainActivity extends Activity {
    private GameView game;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        game = new GameView(this);
        setContentView(game);
    }

    @Override protected void onResume() {
        super.onResume();
        game.resume();
    }

    @Override protected void onPause() {
        game.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        game.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!game.back()) super.onBackPressed();
    }
}
