package com.detector.esp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.os.Bundle;
import android.content.Intent;
import android.util.Log;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.detector.esp.ui.ZoomDialView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.detector.esp.camera.CameraHelper;
import com.detector.esp.detector.YoloDetector;
import com.detector.esp.preprocess.CpuPreprocessor;
import com.detector.esp.ui.OverlayView;
import com.detector.esp.utils.DetectResult;
import com.detector.esp.utils.DetectResultPool;
import com.detector.esp.utils.Lang;
import com.detector.esp.utils.DetectionStabilizer;

import java.util.List;
import java.util.Locale;

/**
 * 主 Activity — 实时 ESP 检测 + 设置
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final String PREFS_NAME = "esp_settings";

    private TextureView textureView;
    private OverlayView overlayView;
    private TextView zoomText;
    private TextView settingsBtn;

    // iPhone 风格变焦
    private ZoomDialView zoomDial;
    private ScaleGestureDetector scaleGestureDetector;
    private float currentZoom = 1.0f;

    private CameraHelper cameraHelper;
    private YoloDetector detector;
    private CpuPreprocessor cpuPreprocessor;
    private DetectResultPool resultPool;
    private DetectionStabilizer stabilizer;

    private Thread inferenceThread;
    private volatile boolean running = false;

    private int frameCount = 0;
    private long lastFpsTime = 0;
    private int currentFps = 0;

    // GPS
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;
    private volatile int satelliteCount = 0;
    private long lastCoordUpdateTime = 0;
    private double lastLat = 0, lastLon = 0;

    // 类别过滤设置
    private boolean enablePerson = true;
    private boolean enableVehicle = true;
    private boolean enableAnimal = true;
    private boolean enableObject = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.texture_view);
        overlayView = findViewById(R.id.overlay_view);
        zoomText = findViewById(R.id.zoom_text);
        settingsBtn = findViewById(R.id.settings_btn);
        zoomDial = findViewById(R.id.zoom_dial);

        Lang.load(this);
        loadSettings();
        setupZoomControl();
        settingsBtn.setOnClickListener(v -> showSettingsDialog());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startPipeline();
            startGps();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION},
                    CAMERA_PERMISSION_CODE);
        }
    }

    private void setupZoomControl() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (cameraHelper == null) return true;
                currentZoom *= detector.getScaleFactor();
                currentZoom = Math.max(1.0f, Math.min(1000.0f, currentZoom));
                applyZoom(currentZoom);
                zoomDial.setZoom(currentZoom);
                return true;
            }
        });

        textureView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return true;
        });

        zoomDial.setOnZoomChangeListener(zoom -> {
            currentZoom = zoom;
            applyZoom(zoom);
        });
    }

    private void applyZoom(float zoom) {
        if (cameraHelper == null) return;
        cameraHelper.setZoom(zoom);
        overlayView.setCurrentZoom(zoom);

        if (zoom < 10) {
            zoomText.setText(String.format(Locale.US, "%.1fx", zoom));
        } else {
            zoomText.setText(String.format(Locale.US, "%.0fx", zoom));
        }

        float swZoom = cameraHelper.getSoftwareZoomFactor();
        if (swZoom > 1.0f) {
            textureView.setScaleX(swZoom);
            textureView.setScaleY(swZoom);
        } else {
            textureView.setScaleX(1.0f);
            textureView.setScaleY(1.0f);
        }
    }

    // ==================== 设置 ====================

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        enablePerson = prefs.getBoolean("person", true);
        enableVehicle = prefs.getBoolean("vehicle", true);
        enableAnimal = prefs.getBoolean("animal", true);
        enableObject = prefs.getBoolean("object", true);
    }

    private void saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean("person", enablePerson)
                .putBoolean("vehicle", enableVehicle)
                .putBoolean("animal", enableAnimal)
                .putBoolean("object", enableObject)
                .apply();
    }

    private void applyFilterToDetector() {
        if (detector != null) {
            detector.setEnabledCategories(enablePerson, enableVehicle, enableAnimal, enableObject);
        }
    }

    private void showSettingsDialog() {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding((int)(20*dp), (int)(16*dp), (int)(20*dp), (int)(8*dp));

        CheckBox cbPerson = makeCheckBox(Lang.person(), enablePerson, dp);
        CheckBox cbVehicle = makeCheckBox(Lang.vehicle(), enableVehicle, dp);
        CheckBox cbAnimal = makeCheckBox(Lang.animal(), enableAnimal, dp);
        CheckBox cbObject = makeCheckBox(Lang.objects(), enableObject, dp);

        layout.addView(cbPerson);
        layout.addView(cbVehicle);
        layout.addView(cbAnimal);
        layout.addView(cbObject);

        // 全选/全不选按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(12*dp), 0, 0);

        TextView allOn = new TextView(this);
        allOn.setText(Lang.enableAll());
        allOn.setTextColor(0xFF00E676);
        allOn.setTextSize(15);
        allOn.setPadding((int)(8*dp), (int)(8*dp), (int)(20*dp), (int)(8*dp));
        allOn.setOnClickListener(v -> {
            cbPerson.setChecked(true); cbVehicle.setChecked(true);
            cbAnimal.setChecked(true); cbObject.setChecked(true);
        });

        TextView allOff = new TextView(this);
        allOff.setText(Lang.disableAll());
        allOff.setTextColor(0xFFFF1744);
        allOff.setTextSize(15);
        allOff.setPadding((int)(8*dp), (int)(8*dp), (int)(8*dp), (int)(8*dp));
        allOff.setOnClickListener(v -> {
            cbPerson.setChecked(false); cbVehicle.setChecked(false);
            cbAnimal.setChecked(false); cbObject.setChecked(false);
        });

        btnRow.addView(allOn);
        btnRow.addView(allOff);
        layout.addView(btnRow);

        // 卫星查看器按钮
        TextView satBtn = new TextView(this);
        satBtn.setText("🛰 " + Lang.satellite());
        satBtn.setTextColor(0xFF00E676);
        satBtn.setTextSize(16);
        satBtn.setPadding((int)(8*dp), (int)(16*dp), 0, (int)(8*dp));
        satBtn.setOnClickListener(v2 -> startActivity(new Intent(this, SatelliteActivity.class)));
        layout.addView(satBtn);

        // 语言切换
        TextView langBtn = new TextView(this);
        langBtn.setText("🌐 " + Lang.language());
        langBtn.setTextColor(0xFF00BCD4);
        langBtn.setTextSize(16);
        langBtn.setPadding((int)(8*dp), (int)(12*dp), 0, (int)(8*dp));
        langBtn.setOnClickListener(v3 -> {
            Lang.toggleLanguage(this);
            // 刷新对话框
            ((android.app.Dialog) langBtn.getTag()).dismiss();
            showSettingsDialog();
        });
        layout.addView(langBtn);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(Lang.settings())
                .setView(layout)
                .setPositiveButton(Lang.ok(), (d, w) -> {
                    enablePerson = cbPerson.isChecked();
                    enableVehicle = cbVehicle.isChecked();
                    enableAnimal = cbAnimal.isChecked();
                    enableObject = cbObject.isChecked();
                    saveSettings();
                    applyFilterToDetector();
                })
                .setNegativeButton(Lang.cancel(), null)
                .create();
        langBtn.setTag(dialog);
        dialog.show();
    }

    private CheckBox makeCheckBox(String text, boolean checked, float dp) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setTextColor(0xFFFFFFFF);
        cb.setTextSize(16);
        cb.setPadding((int)(4*dp), (int)(8*dp), 0, (int)(8*dp));
        return cb;
    }

    // ==================== Pipeline ====================

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION_CODE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startPipeline();
            startGps();
        } else {
            finish();
        }
    }

    private void startPipeline() {
        try {
            // 使用 SplashActivity 预加载的组件（零等待）
            if (AppPreloader.ready) {
                detector = AppPreloader.detector;
                cpuPreprocessor = AppPreloader.preprocessor;
                resultPool = AppPreloader.resultPool;
                stabilizer = AppPreloader.stabilizer;
            } else {
                // 回退：直接加载（从设置页返回等情况）
                detector = new YoloDetector(this);
                cpuPreprocessor = new CpuPreprocessor(detector.getInputSize());
                resultPool = new DetectResultPool();
                stabilizer = new DetectionStabilizer();
            }
            applyFilterToDetector();

            cameraHelper = new CameraHelper(this, textureView);
            cameraHelper.start();

            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                int rot = cameraHelper.getSensorOrientation();
                cpuPreprocessor.setRotation(rot);
                // 动态 FOV 传给 OverlayView（适配所有手机）
                float vFov = cameraHelper.getVFovDegrees();
                overlayView.setFov(vFov);
                Log.i(TAG, "前处理旋转: " + rot + "° FOV: " + vFov + "°");
            }).start();

            running = true;
            inferenceThread = new Thread(this::inferenceLoop, "InferenceThread");
            inferenceThread.setPriority(Thread.MAX_PRIORITY);
            inferenceThread.start();

            overlayView.startRendering();
            Log.i(TAG, "Pipeline 已启动");
        } catch (Exception e) {
            Log.e(TAG, "Pipeline 启动失败", e);
        }
    }

    private void inferenceLoop() {
        Log.i(TAG, "推理线程已启动");
        int totalFrames = 0;

        while (running) {
            Image image = cameraHelper.pollLatestFrame();
            if (image == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                continue;
            }
            totalFrames++;

            try {
                long t0 = System.currentTimeMillis();
                float swZoom = cameraHelper.getSoftwareZoomFactor();
                byte[] rgb = cpuPreprocessor.processYuvImage(image, swZoom);
                image.close();
                long t1 = System.currentTimeMillis();

                detector.detect(rgb, resultPool);
                long t2 = System.currentTimeMillis();

                float latencyMs = t2 - t0;

                if (totalFrames % 60 == 0) {
                    Log.i(TAG, "计时: 前处理=" + (t1 - t0) + "ms 推理=" + (t2 - t1) + "ms 总=" + (t2 - t0) + "ms");
                }

                List<DetectResult> rawResults = resultPool.getDisplayResults();
                for (DetectResult dr : rawResults) {
                    float[] real = cpuPreprocessor.removePadding(dr.left, dr.top, dr.right, dr.bottom);
                    dr.left = real[0]; dr.top = real[1]; dr.right = real[2]; dr.bottom = real[3];
                }

                List<DetectResult> stableResults = stabilizer.update(rawResults);

                frameCount++;
                long now = System.currentTimeMillis();
                if (now - lastFpsTime >= 1000) {
                    currentFps = frameCount;
                    frameCount = 0;
                    lastFpsTime = now;
                }

                overlayView.setResults(stableResults, currentFps, latencyMs);

            } catch (Exception e) {
                image.close();
                Log.e(TAG, "推理出错", e);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPipeline();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (detector != null && !running) {
            cameraHelper.start();
            running = true;
            inferenceThread = new Thread(this::inferenceLoop, "InferenceThread");
            inferenceThread.setPriority(Thread.MAX_PRIORITY);
            inferenceThread.start();
            overlayView.startRendering();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPipeline();
        if (detector != null) { detector.close(); detector = null; }
    }

    @SuppressWarnings("MissingPermission")
    private void startGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                // 速度实时更新
                overlayView.setGpsSpeed(loc.getSpeed());
                overlayView.setGpsSatellites(satelliteCount);

                // 坐标每秒更新一次
                long now = System.currentTimeMillis();
                if (now - lastCoordUpdateTime >= 1000) {
                    lastLat = loc.getLatitude();
                    lastLon = loc.getLongitude();
                    overlayView.setGpsCoord(lastLat, lastLon);
                    lastCoordUpdateTime = now;
                }
            }
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };

        gnssCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                int count = 0;
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    if (status.usedInFix(i)) count++;
                }
                satelliteCount = count;
            }
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100, 0, locationListener);  // 100ms 更新，速度实时
            // 网络定位备用（室内也能用）
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0, locationListener);
            }
            locationManager.registerGnssStatusCallback(gnssCallback);

            // 读取上次已知位置先显示
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) {
                overlayView.setGpsData(last.getLatitude(), last.getLongitude(), last.getSpeed(), 0);
            }

            Log.i(TAG, "GPS 已启动");
        } catch (Exception e) {
            Log.e(TAG, "GPS 启动失败", e);
        }
    }

    private void stopGps() {
        if (locationManager != null) {
            locationManager.removeUpdates(location -> {});
            if (gnssCallback != null) locationManager.unregisterGnssStatusCallback(gnssCallback);
        }
    }

    private void stopPipeline() {
        running = false;
        overlayView.stopRendering();
        if (inferenceThread != null) {
            try { inferenceThread.join(1000); } catch (InterruptedException ignored) {}
            inferenceThread = null;
        }
        if (cameraHelper != null) cameraHelper.stop();
    }
}
