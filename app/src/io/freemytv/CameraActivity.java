package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The security cameras, full screen: each one's RTSP stream (config.properties: cameras=Name|rtsp://...,Name|rtsp://...).
 * Left and Right switch cameras, Back returns home. A dropped stream connects again by itself. Down selects the camera
 * makers' own TV apps (bottom right, when installed: the cameras RTSP can't show), OK opens one.
 */
public final class CameraActivity extends Activity implements SurfaceHolder.Callback, RtspPlayer.Listener {
    private static final long LABEL_MS = 4000;
    /** Waits between tries; then it stops: a wrong password tried over and over locks the camera (EZVIZ, Hikvision). */
    private static final long[] RETRY_MS = {3000, 10_000, 30_000, 60_000};
    /** Camera makers' TV apps, shown as shortcuts when installed. */
    private static final String[] CAMERA_APPS = {"com.ezviz.tv"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String[]> cameras = new ArrayList<>();   // {name, url}
    private int index, failures;
    private SurfaceView video;
    private boolean surfaceReady, resumed;
    private RtspPlayer player;
    private TextView label, status;
    private final List<TextView> appButtons = new ArrayList<>();
    private final List<Intent> appIntents = new ArrayList<>();
    private LinearLayout appBar;
    private int appIndex = -1;   // the selected app shortcut; -1: none (the remote switches cameras)

    private final Runnable retry = this::play;
    private final Runnable hideLabel = () -> {
        label.animate().alpha(0).setDuration(400);
        if (appBar != null && appIndex < 0) appBar.animate().alpha(0).setDuration(400);
    };

    /** The cameras in config.properties, as {name, url}; empty when there are none. */
    static List<String[]> cameras() {
        List<String[]> out = new ArrayList<>();
        for (String entry : Config.CAMERAS.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int bar = e.indexOf('|');
            String name = bar > 0 ? e.substring(0, bar).trim() : Ui.tr("Camera") + " " + (out.size() + 1);
            String url = bar >= 0 ? e.substring(bar + 1).trim() : e;
            if (url.startsWith("rtsp://")) out.add(new String[] {name, url});
        }
        return out;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameras.addAll(cameras());
        if (cameras.isEmpty()) {
            finish();
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        label = text(22, 0xF2FFFFFF);
        label.setBackgroundColor(0x99000000);
        label.setPadding(Math.round(20 * d), Math.round(10 * d), Math.round(20 * d), Math.round(10 * d));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(Math.round(32 * d), Math.round(24 * d), 0, 0);
        root.addView(label, lp);
        status = text(20, 0xB3FFFFFF);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        addAppShortcuts(root, d);
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Bottom right: a pill per installed camera app (its icon and name), with "▼" while none is selected. */
    private void addAppShortcuts(FrameLayout root, float d) {
        PackageManager pm = getPackageManager();
        appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        for (String pkg : CAMERA_APPS) {
            Intent launch = HomeApps.launchIntent(pm, pkg);
            if (launch == null) continue;
            TextView b = text(18, 0xF2FFFFFF);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(Math.round(14 * d), Math.round(8 * d), Math.round(20 * d), Math.round(8 * d));
            b.setCompoundDrawablePadding(Math.round(10 * d));
            try {
                Drawable icon = pm.getApplicationIcon(pkg);
                int s = Math.round(28 * d);
                icon.setBounds(0, 0, s, s);
                b.setCompoundDrawables(icon, null, null, null);
                b.setText(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(Math.round(12 * d));
            appBar.addView(b, lp);
            appButtons.add(b);
            appIntents.add(launch);
        }
        if (appButtons.isEmpty()) {
            appBar = null;
            return;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, Math.round(32 * d), Math.round(28 * d));
        root.addView(appBar, lp);
        selectApp(-1);
    }

    private void selectApp(int i) {
        appIndex = i;
        float d = getResources().getDisplayMetrics().density;
        for (int j = 0; j < appButtons.size(); j++) {
            TextView b = appButtons.get(j);
            boolean on = j == i;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(100 * d);
            bg.setColor(on ? 0xF2FFFFFF : 0x99000000);
            b.setBackground(bg);
            b.setTextColor(on ? 0xFF1D1D1F : 0xF2FFFFFF);
            b.animate().scaleX(on ? 1.06f : 1).scaleY(on ? 1.06f : 1).setDuration(150);
        }
        appBar.animate().cancel();
        appBar.setAlpha(1);
        handler.removeCallbacks(hideLabel);
        handler.postDelayed(hideLabel, LABEL_MS);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Ui.medium);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        failures = 0;
        if (surfaceReady) play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacksAndMessages(null);
        stopPlayer();
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (resumed) play();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopPlayer();
    }

    private void stopPlayer() {
        if (player != null) player.stop();
        player = null;
    }

    private void play() {
        handler.removeCallbacks(retry);
        stopPlayer();
        if (!surfaceReady) return;
        String[] cam = cameras.get(index);
        label.setText(cameras.size() > 1 ? cam[0] + "   ‹ " + (index + 1) + "/" + cameras.size() + " ›" : cam[0]);
        label.animate().cancel();
        label.setAlpha(1);
        if (appBar != null) {
            appBar.animate().cancel();
            appBar.setAlpha(1);
        }
        handler.removeCallbacks(hideLabel);
        handler.postDelayed(hideLabel, LABEL_MS);
        showStatus(Ui.tr("Connecting…"));
        player = new RtspPlayer(cam[1], video.getHolder().getSurface(), this);
        player.start();
    }

    // RtspPlayer.Listener: called on its thread
    @Override public void onPlaying() {
        handler.post(() -> {
            failures = 0;
            status.setVisibility(View.GONE);
        });
    }

    @Override public void onFailed(String why) {
        handler.post(() -> {
            AppLog.w("camera " + cameras.get(index)[0] + ": " + why);
            if (resumed) retryLater();
        });
    }

    private void retryLater() {
        handler.removeCallbacks(retry);
        if (failures >= RETRY_MS.length) {
            showStatus(Ui.tr("Can't connect to the camera. Check its address and password, then press OK."));
            return;
        }
        showStatus(Ui.tr("Can't connect to the camera. Trying again…"));
        handler.postDelayed(retry, RETRY_MS[failures++]);
    }

    private void showStatus(String s) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (appBar != null) {
            if (appIndex < 0 && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                selectApp(appButtons.size() - 1);
                return true;
            }
            if (appIndex >= 0) {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_BACK:
                        selectApp(-1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (appIndex > 0) selectApp(appIndex - 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (appIndex < appButtons.size() - 1) selectApp(appIndex + 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER: {
                        Intent launch = new Intent(appIntents.get(appIndex)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(launch);
                        } catch (Exception ex) {
                            AppLog.w("couldn't open " + appButtons.get(appIndex).getText() + ": " + ex.getMessage());
                        }
                        return true;
                    }
                    default:
                }
            }
        }
        if (cameras.size() > 1 && (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            int step = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1;
            index = (index + step + cameras.size()) % cameras.size();
            failures = 0;
            play();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {   // shows the name again
            if (failures >= RETRY_MS.length) {   // gave up: OK tries again
                failures = 0;
                play();
                return true;
            }
            label.animate().cancel();
            label.setAlpha(1);
            handler.removeCallbacks(hideLabel);
            handler.postDelayed(hideLabel, LABEL_MS);
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
