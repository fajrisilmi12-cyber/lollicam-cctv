package id.my.lollicam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final int CAMERA_PERMISSION = 10;
    private SurfaceView preview;
    private EditText serverInput, tokenInput, fpsInput, qualityInput;
    private TextView status;
    private Camera camera;
    private Camera.Size frameSize;
    private final ExecutorService uploader = Executors.newSingleThreadExecutor();
    private final AtomicBoolean uploadBusy = new AtomicBoolean(false);
    private volatile boolean streaming;
    private long lastCapture;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        buildUi();
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); return e;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 20, 24, 20);
        preview = new SurfaceView(this); root.addView(preview, new LinearLayout.LayoutParams(-1, dp(260))); preview.getHolder().addCallback(this);
        serverInput = field("https://cctv.domain.my.id", prefs.getString("server", "")); root.addView(serverInput);
        tokenInput = field("Camera token", prefs.getString("token", "")); root.addView(tokenInput);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        fpsInput = field("FPS", prefs.getString("fps", "2")); qualityInput = field("Quality", prefs.getString("quality", "45"));
        row.addView(fpsInput, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(qualityInput, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(row);
        Button start = new Button(this); start.setText("Mulai kamera"); root.addView(start); start.setOnClickListener(v -> startStreaming());
        Button stop = new Button(this); stop.setText("Berhenti"); root.addView(stop); stop.setOnClickListener(v -> stopCamera());
        status = new TextView(this); status.setPadding(0, 12, 0, 0); status.setText("Belum aktif"); root.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void startStreaming() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION); return;
        }
        String server = serverInput.getText().toString().trim().replaceAll("/+$", "");
        String token = tokenInput.getText().toString().trim();
        if (!(server.startsWith("https://") || server.startsWith("http://")) || token.length() < 24) {
            Toast.makeText(this, "URL atau camera token belum valid", Toast.LENGTH_LONG).show(); return;
        }
        prefs.edit().putString("server", server).putString("token", token).putString("fps", fpsInput.getText().toString()).putString("quality", qualityInput.getText().toString()).apply();
        streaming = true; acquireWakeLock(); openCamera();
    }

    private void openCamera() {
        if (camera != null || !streaming || !preview.getHolder().getSurface().isValid()) return;
        try {
            camera = Camera.open();
            Camera.Parameters p = camera.getParameters();
            Camera.Size selected = selectSize(p.getSupportedPreviewSizes());
            p.setPreviewSize(selected.width, selected.height); p.setPreviewFormat(ImageFormat.NV21);
            List<String> focus = p.getSupportedFocusModes();
            if (focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            camera.setParameters(p); frameSize = selected; camera.setDisplayOrientation(90); camera.setPreviewDisplay(preview.getHolder()); camera.setPreviewCallback(this); camera.startPreview();
            setStatus("Aktif — " + selected.width + "×" + selected.height);
        } catch (Exception e) { setStatus("Kamera gagal: " + e.getMessage()); stopCamera(); }
    }

    private Camera.Size selectSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0); long bestDistance = Long.MAX_VALUE;
        for (Camera.Size s : sizes) { long d = Math.abs(s.width - 640L) + Math.abs(s.height - 480L); if (d < bestDistance) { best = s; bestDistance = d; } }
        return best;
    }

    @Override public void onPreviewFrame(byte[] data, Camera ignored) {
        if (!streaming || frameSize == null || uploadBusy.get()) return;
        int fps = clamp(parse(fpsInput.getText().toString(), 2), 1, 5);
        long now = System.currentTimeMillis(); if (now - lastCapture < 1000L / fps) return; lastCapture = now;
        int quality = clamp(parse(qualityInput.getText().toString(), 45), 25, 70);
        YuvImage image = new YuvImage(data, ImageFormat.NV21, frameSize.width, frameSize.height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); image.compressToJpeg(new Rect(0, 0, frameSize.width, frameSize.height), quality, out);
        byte[] jpeg = out.toByteArray(); uploadBusy.set(true);
        uploader.execute(() -> upload(jpeg));
    }

    private void upload(byte[] jpeg) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(prefs.getString("server", "") + "/api/frame"); c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(jpeg.length);
            c.setRequestProperty("Content-Type", "image/jpeg"); c.setRequestProperty("Authorization", "Bearer " + prefs.getString("token", ""));
            OutputStream os = c.getOutputStream(); os.write(jpeg); os.close(); int code = c.getResponseCode();
            if (code != 204) setStatus("Server menjawab HTTP " + code); else setStatus("Live — frame terkirim");
        } catch (Exception e) { setStatus("Koneksi gagal: " + e.getClass().getSimpleName()); }
        finally { if (c != null) c.disconnect(); uploadBusy.set(false); }
    }

    private int parse(String s, int fallback) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; } }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private void setStatus(String text) { runOnUiThread(() -> status.setText(text)); }

    private void acquireWakeLock() {
        if (wakeLock == null) { PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE); wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LolliCam:Camera"); }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void stopCamera() {
        streaming = false;
        if (camera != null) { try { camera.setPreviewCallback(null); camera.stopPreview(); camera.release(); } catch (Exception ignored) {} camera = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); setStatus("Berhenti");
    }

    @Override public void surfaceCreated(SurfaceHolder h) { if (streaming) openCamera(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int x) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { stopCamera(); }
    @Override protected void onDestroy() { stopCamera(); uploader.shutdownNow(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int request, String[] p, int[] result) { super.onRequestPermissionsResult(request, p, result); if (request == CAMERA_PERMISSION && result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) startStreaming(); }
}
