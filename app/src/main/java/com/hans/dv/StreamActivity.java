package com.hans.dv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 全屏显示 PC 画面。单击: 统计信息; 双击: 完整显示 / 填满屏幕 (VR 盒子用填满); 返回键退出。 */
public class StreamActivity extends Activity implements StreamClient.Listener {

    private FrameLayout root;
    private SurfaceView surfaceView;
    private TextView statusText, statsText;
    private StreamClient client;
    private WifiManager.WifiLock wifiLock;
    private int videoW, videoH;
    private boolean fillMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        preferHighestRefreshRate();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(200, 205, 215));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        statsText = new TextView(this);
        statsText.setTextColor(Color.rgb(120, 255, 140));
        statsText.setBackgroundColor(0x99000000);
        statsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int p = Math.round(6 * getResources().getDisplayMetrics().density);
        statsText.setPadding(p, p / 2, p, p / 2);
        statsText.setVisibility(View.GONE);
        root.addView(statsText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        fillMode = getSharedPreferences("dv", MODE_PRIVATE).getBoolean("fill", false);
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                statsText.setVisibility(statsText.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fillMode = !fillMode;
                getSharedPreferences("dv", MODE_PRIVATE).edit().putBoolean("fill", fillMode).apply();
                applyAspect();
                Toast.makeText(StreamActivity.this,
                        fillMode ? "填满屏幕 (上下会裁掉一点, 适合 VR 盒子)" : "完整显示", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        root.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyAspect();
        });

        setContentView(root);
        hideSystemBars();

        final String host = getIntent().getStringExtra("host");
        final int port = getIntent().getIntExtra("port", MainActivity.TCP_PORT);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                client = new StreamClient(host, port, holder.getSurface(), StreamActivity.this);
                client.start();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (client != null) {
                    client.stop();
                    client = null;
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wifiLock == null) {
            @SuppressWarnings("deprecation")
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "dv:stream");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ------------------------------------------------------------ Listener (来自后台线程)

    @Override
    public void onStatus(String text) {
        runOnUiThread(() -> {
            statusText.setText(text == null ? "" : text);
            statusText.setVisibility(text == null ? View.GONE : View.VISIBLE);
        });
    }

    @Override
    public void onVideoSize(int width, int height) {
        runOnUiThread(() -> {
            if (width == videoW && height == videoH) return;
            videoW = width;
            videoH = height;
            applyAspect();
        });
    }

    @Override
    public void onStats(String text) {
        runOnUiThread(() -> statsText.setText(text));
    }

    // ------------------------------------------------------------ 显示

    /** 按视频比例缩放, 不拉伸。完整显示 = 留黑边; 填满 = 铺满屏幕, 超出部分裁掉。 */
    private void applyAspect() {
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0 || videoW == 0 || videoH == 0) return;
        double sx = (double) W / videoW, sy = (double) H / videoH;
        double scale = fillMode ? Math.max(sx, sy) : Math.min(sx, sy);
        int w = (int) Math.round(videoW * scale), h = (int) Math.round(videoH * scale);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(lp);
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** 高刷手机切到最高刷新率, 画面更新更及时。 */
    @SuppressWarnings("deprecation")
    private void preferHighestRefreshRate() {
        if (Build.VERSION.SDK_INT < 23) return;
        Display d = getWindowManager().getDefaultDisplay();
        Display.Mode cur = d.getMode();
        Display.Mode best = cur;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == cur.getPhysicalWidth()
                    && m.getPhysicalHeight() == cur.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) {
                best = m;
            }
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
    }
}
