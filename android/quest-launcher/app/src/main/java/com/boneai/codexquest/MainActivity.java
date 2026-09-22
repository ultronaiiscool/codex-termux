package com.boneai.codexquest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        TextView status = new TextView(this);
        status.setText("Codex App Server\nws://127.0.0.1:4500");
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button start = new Button(this);
        start.setText("Start backend");
        start.setOnClickListener(v -> {
            startBackend();
            status.setText("Starting Codex App Server…\nws://127.0.0.1:4500");
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop backend");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CodexService.class));
            status.setText("Codex App Server stopped");
        });
        root.addView(stop);

        setContentView(root);
        startBackend();
    }

    private void startBackend() {
        Intent intent = new Intent(this, CodexService.class);
        startForegroundService(intent);
    }
}
