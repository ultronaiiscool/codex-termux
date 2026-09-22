package com.boneai.codexquest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class CodexService extends Service {
    private static final String TAG = "CodexQuest";
    private static final String CHANNEL_ID = "codex_app_server";
    private static final int NOTIFICATION_ID = 4500;
    private static final int PORT = 4500;
    private static final String LISTEN_URL = "ws://127.0.0.1:" + PORT;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile Process codexProcess;
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("Starting Codex App Server"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Process process = codexProcess;
        if (process == null || !process.isAlive()) {
            workers.execute(this::startCodex);
        }
        return START_STICKY;
    }

    private synchronized void startCodex() {
        if (stopping) {
            return;
        }
        Process existing = codexProcess;
        if (existing != null && existing.isAlive()) {
            return;
        }

        try {
            File filesDir = getFilesDir();
            File codexHome = new File(filesDir, "codex");
            File cacheDir = new File(getCacheDir(), "codex");
            File logDir = new File(codexHome, "logs");
            ensureDirectory(codexHome);
            ensureDirectory(cacheDir);
            ensureDirectory(logDir);

            String nativeLibraryDir = getApplicationInfo().nativeLibraryDir;
            File codex = new File(nativeLibraryDir, "libcodex.so");
            File codeModeHost = new File(nativeLibraryDir, "libcodex_code_mode_host.so");
            File libcxx = new File(nativeLibraryDir, "libc++_shared.so");
            requireFile(codex, "Codex executable");
            requireFile(codeModeHost, "Code Mode host");
            requireFile(libcxx, "C++ runtime");

            Log.i(TAG, "[CodexQuest] Starting Codex App Server");
            Log.i(TAG, "[CodexQuest] Platform: Android ARM64");
            Log.i(TAG, "[CodexQuest] CODEX_HOME: " + codexHome);
            Log.i(TAG, "[CodexQuest] Bind: " + LISTEN_URL);

            ProcessBuilder builder = new ProcessBuilder(
                    codex.getAbsolutePath(),
                    "app-server",
                    "--listen",
                    LISTEN_URL);
            builder.directory(filesDir);
            builder.redirectErrorStream(true);

            Map<String, String> env = builder.environment();
            env.put("CODEX_HOME", codexHome.getAbsolutePath());
            env.put("CODEX_SELF_EXE", codex.getAbsolutePath());
            env.put("CODEX_INTERNAL_APP_SERVER_REMOTE_CONTROL_DISABLED", "1");
            env.put("HOME", filesDir.getAbsolutePath());
            env.put("TMPDIR", cacheDir.getAbsolutePath());
            env.put("XDG_CACHE_HOME", cacheDir.getAbsolutePath());
            env.put("SHELL", "/system/bin/sh");

            String oldLdLibraryPath = env.get("LD_LIBRARY_PATH");
            env.put(
                    "LD_LIBRARY_PATH",
                    nativeLibraryDir + (oldLdLibraryPath == null || oldLdLibraryPath.isEmpty()
                            ? ""
                            : ":" + oldLdLibraryPath));

            String oldPath = env.get("PATH");
            String androidPath = nativeLibraryDir + ":/system/bin:/system/xbin";
            env.put(
                    "PATH",
                    androidPath + (oldPath == null || oldPath.isEmpty() ? "" : ":" + oldPath));

            Process started = builder.start();
            codexProcess = started;
            workers.execute(() -> copyLogs(started, new File(logDir, "codex-app-server.log")));
            workers.execute(() -> waitForReadiness(started));
            workers.execute(() -> monitorExit(started));
        } catch (Throwable error) {
            Log.e(TAG, "[CodexQuest] Failed to start App Server", error);
            updateNotification("Codex App Server failed: " + safeMessage(error));
            stopSelf();
        }
    }

    private void waitForReadiness(Process process) {
        while (!stopping && process.isAlive()) {
            if (readyz()) {
                Log.i(TAG, "[CodexQuest] App Server ready");
                updateNotification("Codex App Server ready on 127.0.0.1:" + PORT);
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean readyz() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            socket.setSoTimeout(500);
            OutputStream out = socket.getOutputStream();
            out.write((
                    "GET /readyz HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + PORT + "\r\n" +
                    "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            return statusLine != null && statusLine.contains(" 200 ");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void copyLogs(Process process, File logFile) {
        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, line);
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (Exception error) {
            if (!stopping) {
                Log.e(TAG, "[CodexQuest] Failed while reading App Server output", error);
            }
        }
    }

    private void monitorExit(Process process) {
        try {
            int exitCode = process.waitFor();
            if (!stopping) {
                Log.e(TAG, "[CodexQuest] App Server exited with code " + exitCode);
                updateNotification("Codex App Server exited (" + exitCode + ")");
                stopSelf();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Could not create directory: " + directory);
        }
    }

    private static void requireFile(File file, String label) {
        if (!file.isFile()) {
            throw new IllegalStateException(label + " not found: " + file);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Codex App Server",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Codex Quest Backend")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification(text));
    }

    @Override
    public void onDestroy() {
        stopping = true;
        Process process = codexProcess;
        codexProcess = null;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        workers.shutdownNow();
        Log.i(TAG, "[CodexQuest] App Server stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
