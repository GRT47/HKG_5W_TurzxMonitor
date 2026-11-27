package com.hkg.huddisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

/**
 * Fullscreen 1920x720 activity for t88 secondary HUD display.
 */
public final class MainActivity extends Activity {

    public static final String PREFS_NAME = "hkg_hud_prefs";
    public static final String KEY_DEMO = "demo_mode";
    public static final String KEY_DEMO_PARK = "demo_park";
    public static final String KEY_HUB_IP = "hub_ip";
    public static final String KEY_COMMA_IP = "comma_ip";
    public static final String KEY_COMMA_DIRECT = "comma_direct";
    public static final String KEY_THEME = "theme_mode";
    public static final String KEY_PT = "pt_mode";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_STANDBY = "standby_screen_off";
    public static final String KEY_STATUS_PILL = "show_status_pill";
    public static final String KEY_CAR3D = "car3d_enabled";
    public static final String KEY_CAR3D_COLOR = "car3d_color";
    public static final String KEY_AUTO_UPDATE = "auto_update";
    public static final String KEY_UPDATE_REPO = "update_repo";
    public static final String KEY_UPDATE_BRANCH = "update_branch";
    public static final String KEY_DISMISSED_UPDATE_VER = "dismissed_update_ver";

    private static final long AUTO_UPDATE_INITIAL_DELAY_MS = 8000L;
    private static final long AUTO_UPDATE_INTERVAL_MS = 3L * 3600L * 1000L;

    private HudView hudView;
    private HudVizBgView vizBgView;
    private Car3dView car3dView;
    private HudOverlayView overlayView;
    private TelemetryClient telemetryClient;
    private HudDebugWebServer debugWebServer;
    private AppUpdater appUpdater;
    private SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean manualModeActive = false;
    private volatile boolean pendingPermissionInstall = false;
    private final Runnable autoUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (prefs != null && prefs.getBoolean(KEY_AUTO_UPDATE, true) && appUpdater != null) {
                appUpdater.checkForUpdateAsync(null, (ok, avail, msg) -> uiHandler.post(() -> {
                    if (hudView != null) {
                        hudView.getRenderer().setAvailableUpdateVersion(avail != null ? avail.versionName : "");
                    }
                    if (ok && avail != null && !isFinishing() && !isDrivingNow()) {
                        String dismissed = prefs.getString(KEY_DISMISSED_UPDATE_VER, "");
                        if (!avail.versionName.equals(dismissed)) {
                            showUpdateAvailableDialog(avail);
                        }
                    }
                }));
            }
            uiHandler.postDelayed(this, AUTO_UPDATE_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        );

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applyLaunchIntent(getIntent());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        hudView = new HudView(this);
        hudView.setBackgroundColor(Color.BLACK);
        vizBgView = new HudVizBgView(this, hudView.getRenderer());
        car3dView = new Car3dView(this);
        overlayView = new HudOverlayView(this, hudView.getRenderer());
        overlayView.setCar3dView(car3dView);

        hudView.setVizBgView(vizBgView);
        hudView.setCar3dView(car3dView);
        hudView.setOverlayView(overlayView);

        Runnable openSettings = new Runnable() {
            @Override
            public void run() {
                showSettingsDialog();
            }
        };
        hudView.setOnOpenSettings(openSettings);

        root.addView(hudView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ViewOutlineProvider vizOutline = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float sc = Math.min(view.getWidth() / 864f, view.getHeight() / 576f);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 28f * Math.max(0.25f, sc));
            }
        };

        final FrameLayout.LayoutParams vizBgLp = new FrameLayout.LayoutParams(864, 576, Gravity.TOP | Gravity.START);
        vizBgLp.leftMargin = 16;
        vizBgLp.topMargin = 16;
        vizBgView.setOutlineProvider(vizOutline);
        vizBgView.setClipToOutline(true);
        root.addView(vizBgView, vizBgLp);

        final FrameLayout.LayoutParams carLp = new FrameLayout.LayoutParams(864, 576, Gravity.TOP | Gravity.START);
        carLp.leftMargin = 16;
        carLp.topMargin = 16;
        car3dView.setOutlineProvider(vizOutline);
        car3dView.setClipToOutline(true);
        root.addView(car3dView, carLp);

        final FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(864, 576, Gravity.TOP | Gravity.START);
        overlayLp.leftMargin = 16;
        overlayLp.topMargin = 16;
        root.addView(overlayView, overlayLp);

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int rw = r - l, rh = b - t;
            if (rw <= 0 || rh <= 0) return;
            float sx = rw / HudRenderer.HUD_W;
            float sy = rh / HudRenderer.HUD_H;
            int nx = Math.round(16f * sx);
            int ny = Math.round(16f * sy);
            int nw = Math.round(864f * sx);
            int nh = Math.round(576f * sy);
            if (carLp.leftMargin != nx || carLp.topMargin != ny || carLp.width != nw || carLp.height != nh) {
                vizBgLp.leftMargin = nx;
                vizBgLp.topMargin = ny;
                vizBgLp.width = nw;
                vizBgLp.height = nh;
                vizBgView.requestLayout();
                vizBgView.invalidateOutline();
                carLp.leftMargin = nx;
                carLp.topMargin = ny;
                carLp.width = nw;
                carLp.height = nh;
                car3dView.requestLayout();
                car3dView.invalidateOutline();
                overlayLp.leftMargin = nx;
                overlayLp.topMargin = ny;
                overlayLp.width = nw;
                overlayLp.height = nh;
                overlayView.requestLayout();
            }
        });

        setContentView(root);

        telemetryClient = new TelemetryClient(this);
        appUpdater = new AppUpdater(this);
        AppUpdater.setInstallStatusListener((status, message) -> uiHandler.post(() -> {
            if (isFinishing()) return;
            if (status != PackageInstaller.STATUS_PENDING_USER_ACTION && message != null && !message.isEmpty()) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        }));
        hudView.setTelemetryClient(telemetryClient);
        debugWebServer = new HudDebugWebServer(getAssets(), new HudDebugWebServer.Listener() {
            @Override
            public void onApplyAppConfig(final String mode, final Integer theme, final Integer pt,
                                         final Boolean car3d, final Integer carColor, final Boolean statusPill) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        SharedPreferences.Editor ed = prefs.edit();
                        boolean changed = false;
                        if ("live".equals(mode)) {
                            manualModeActive = false;
                            ed.putBoolean(KEY_DEMO, false);
                            changed = true;
                        } else if ("manual".equals(mode)) {
                            manualModeActive = true;
                        } else if ("demo_drive".equals(mode)) {
                            manualModeActive = false;
                            ed.putBoolean(KEY_DEMO, true);
                            ed.putBoolean(KEY_DEMO_PARK, false);
                            changed = true;
                        } else if ("demo_park".equals(mode)) {
                            manualModeActive = false;
                            ed.putBoolean(KEY_DEMO, true);
                            ed.putBoolean(KEY_DEMO_PARK, true);
                            changed = true;
                        }
                        if (theme != null) { ed.putInt(KEY_THEME, theme.intValue()); changed = true; }
                        if (pt != null) { ed.putInt(KEY_PT, pt.intValue()); changed = true; }
                        if (car3d != null) { ed.putBoolean(KEY_CAR3D, car3d.booleanValue()); changed = true; }
                        if (carColor != null) { ed.putInt(KEY_CAR3D_COLOR, Math.max(0, Math.min(4, carColor.intValue()))); changed = true; }
                        if (statusPill != null) { ed.putBoolean(KEY_STATUS_PILL, statusPill.booleanValue()); changed = true; }
                        if (changed) ed.apply();
                        if (hudView != null) hudView.setManualMode(manualModeActive);
                        applyConfigToRuntime();
                    }
                });
            }

            @Override
            public void onManualStateUpdated() {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (hudView != null) hudView.forceDashboardRefresh();
                    }
                });
            }

            @Override
            public void onCameraPreset(final String preset, final float yawDeg, final float pitchDeg) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (hudView == null) return;
                        HudRenderer r = hudView.getRenderer();
                        boolean parked = r.isLastParked();
                        if ("default".equals(preset)) {
                            if (parked) r.setOrbitAngles(true, 0f, 0f);
                            else r.resetDriveOrbit();
                        } else if ("front".equals(preset)) {
                            r.setOrbitAngles(parked, parked ? -34f : -145f, parked ? -13f : 16f);
                        } else if ("side".equals(preset)) {
                            r.setOrbitAngles(parked, parked ? 56f : -90f, parked ? -17f : 14f);
                        } else if ("rear".equals(preset)) {
                            r.setOrbitAngles(parked, parked ? 146f : -36f, parked ? -12f : 10f);
                        } else if ("top".equals(preset)) {
                            r.setOrbitAngles(parked, 0f, parked ? 33f : 52f);
                        } else {
                            r.setOrbitAngles(parked, yawDeg, pitchDeg);
                        }
                    }
                });
            }

            @Override
            public JSONObject readAppConfigJson() {
                JSONObject app = new JSONObject();
                try {
                    boolean d = prefs.getBoolean(KEY_DEMO, false);
                    boolean dp = prefs.getBoolean(KEY_DEMO_PARK, false);
                    String m = manualModeActive ? "manual" : (!d ? "live" : (dp ? "demo_park" : "demo_drive"));
                    int th = prefs.getInt(KEY_THEME, HudState.THEME_AUTO);
                    int ptm = prefs.getInt(KEY_PT, HudState.PT_AUTO);
                    app.put("mode", m);
                    app.put("theme", th == HudState.THEME_DARK ? "dark" : (th == HudState.THEME_LIGHT ? "light" : "auto"));
                    app.put("pt", ptm == HudState.PT_EV ? "ev" : (ptm == HudState.PT_ICE ? "ice" : "auto"));
                    app.put("car3d", prefs.getBoolean(KEY_CAR3D, true));
                    app.put("carColor", prefs.getInt(KEY_CAR3D_COLOR, 0));
                    app.put("statusPill", prefs.getBoolean(KEY_STATUS_PILL, true));
                } catch (Throwable ignored) {
                }
                return app;
            }

            @Override
            public JSONObject readUpdateStatusJson() {
                return appUpdater != null ? appUpdater.toStatusJson() : new JSONObject();
            }

            @Override
            public void onTriggerUpdateAction(final String action, final String manifestUrl) {
                if (appUpdater == null) return;
                if ("install".equals(action)) {
                    AppUpdater.UpdateInfo avail = appUpdater.getLatestAvailable();
                    if (avail != null && !avail.apkUrl.isEmpty()) {
                        uiHandler.post(() -> {
                            if (!isFinishing()) startUpdateDownloadAndInstall(avail);
                        });
                    } else {
                        appUpdater.checkForUpdateAsync(manifestUrl, (ok, found, msg) -> uiHandler.post(() -> {
                            if (hudView != null) {
                                hudView.getRenderer().setAvailableUpdateVersion(found != null ? found.versionName : "");
                            }
                            if (ok && found != null && !isFinishing()) {
                                startUpdateDownloadAndInstall(found);
                            }
                        }));
                    }
                } else {
                    appUpdater.checkForUpdateAsync(manifestUrl, (ok, found, msg) -> uiHandler.post(() -> {
                        if (hudView != null) {
                            hudView.getRenderer().setAvailableUpdateVersion(found != null ? found.versionName : "");
                        }
                    }));
                }
            }
        });
        hudView.setManualStateProvider(debugWebServer::buildManualSnapshot);
        applyConfigToRuntime();
        telemetryClient.start();
        debugWebServer.start();
        applyImmersiveUi();
        uiHandler.postDelayed(autoUpdateRunnable, AUTO_UPDATE_INITIAL_DELAY_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyLaunchIntent(intent);
        applyConfigToRuntime();
        applyImmersiveUi();
    }

    private void applyLaunchIntent(Intent intent) {
        if (intent == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        boolean changed = false;
        if (intent.hasExtra("demo")) {
            ed.putBoolean(KEY_DEMO, intent.getBooleanExtra("demo", false));
            changed = true;
        }
        if (intent.hasExtra("parked")) {
            ed.putBoolean(KEY_DEMO_PARK, intent.getBooleanExtra("parked", false));
            changed = true;
        }
        String theme = intent.getStringExtra("theme");
        if (theme != null) {
            int t = "dark".equalsIgnoreCase(theme) ? HudState.THEME_DARK
                    : ("light".equalsIgnoreCase(theme) ? HudState.THEME_LIGHT : HudState.THEME_AUTO);
            ed.putInt(KEY_THEME, t);
            changed = true;
        }
        String pt = intent.getStringExtra("pt");
        if (pt != null) {
            int p = "ev".equalsIgnoreCase(pt) ? HudState.PT_EV
                    : ("ice".equalsIgnoreCase(pt) ? HudState.PT_ICE : HudState.PT_AUTO);
            ed.putInt(KEY_PT, p);
            changed = true;
        }
        if (intent.hasExtra("hub_ip")) {
            ed.putString(KEY_HUB_IP, intent.getStringExtra("hub_ip"));
            changed = true;
        }
        if (intent.hasExtra("comma_ip")) {
            ed.putString(KEY_COMMA_IP, intent.getStringExtra("comma_ip"));
            changed = true;
        }
        if (intent.hasExtra("status_pill")) {
            ed.putBoolean(KEY_STATUS_PILL, intent.getBooleanExtra("status_pill", true));
            changed = true;
        }
        if (intent.hasExtra("car3d")) {
            ed.putBoolean(KEY_CAR3D, intent.getBooleanExtra("car3d", true));
            changed = true;
        }
        String carColor = intent.getStringExtra("car_color");
        if (carColor != null) {
            int cid = "graphite".equalsIgnoreCase(carColor) ? 1
                    : ("white".equalsIgnoreCase(carColor) ? 2
                    : ("blue".equalsIgnoreCase(carColor) ? 3
                    : ("black".equalsIgnoreCase(carColor) ? 4 : 0)));
            ed.putInt(KEY_CAR3D_COLOR, cid);
            changed = true;
        }
        if (changed) ed.apply();
    }

    private void applyConfigToRuntime() {
        boolean demo = prefs.getBoolean(KEY_DEMO, false);
        boolean parked = prefs.getBoolean(KEY_DEMO_PARK, false);
        int theme = prefs.getInt(KEY_THEME, HudState.THEME_AUTO);
        int pt = prefs.getInt(KEY_PT, HudState.PT_AUTO);
        boolean standby = prefs.getBoolean(KEY_STANDBY, true);
        boolean pill = prefs.getBoolean(KEY_STATUS_PILL, true);
        boolean car3d = prefs.getBoolean(KEY_CAR3D, true);
        int carColor = prefs.getInt(KEY_CAR3D_COLOR, 0);
        String hubIp = prefs.getString(KEY_HUB_IP, "");
        String commaIp = prefs.getString(KEY_COMMA_IP, "");
        boolean commaDirect = prefs.getBoolean(KEY_COMMA_DIRECT, true);

        if (hudView != null) {
            hudView.setManualMode(manualModeActive);
            hudView.configure(demo, parked, theme, pt, standby, pill, car3d, carColor);
        }
        if (car3dView != null) {
            car3dView.setVisibility(car3d ? View.VISIBLE : View.INVISIBLE);
            car3dView.setRenderPaused(!car3d);
        }
        if (telemetryClient != null) {
            telemetryClient.configure(hubIp, commaIp, commaDirect, demo);
        }
        if (appUpdater != null) {
            String repo = prefs.getString(KEY_UPDATE_REPO, AppUpdater.DEFAULT_REPO);
            String br = prefs.getString(KEY_UPDATE_BRANCH, AppUpdater.DEFAULT_BRANCH);
            appUpdater.configure(repo, br);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (car3dView != null && prefs != null) {
            car3dView.setRenderPaused(!prefs.getBoolean(KEY_CAR3D, true));
        }
        if (pendingPermissionInstall && appUpdater != null) {
            File apk = appUpdater.getPendingInstallApk();
            if (apk != null && apk.isFile()) {
                boolean canInstall = Build.VERSION.SDK_INT < 26
                        || getPackageManager().canRequestPackageInstalls();
                if (canInstall) {
                    pendingPermissionInstall = false;
                    appUpdater.startInstall(this, apk);
                }
            } else {
                pendingPermissionInstall = false;
            }
        }
        applyImmersiveUi();
    }

    @Override
    protected void onPause() {
        if (car3dView != null) car3dView.setRenderPaused(true);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveUi();
    }

    private void applyImmersiveUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(autoUpdateRunnable);
        AppUpdater.setInstallStatusListener(null);
        if (debugWebServer != null) debugWebServer.stop();
        if (telemetryClient != null) telemetryClient.stop();
        super.onDestroy();
    }

    private boolean isDrivingNow() {
        if (telemetryClient == null) return false;
        HudState st = telemetryClient.snapshot();
        if (!st.hubConnected && !st.commaConnected && !st.commaDirectConnected) {
            return false;
        }
        String g = st.gearPos != null ? st.gearPos.trim().toUpperCase(Locale.US) : "P";
        return st.speedKph > 0 || !"P".equals(g);
    }

    private void showUpdateAvailableDialog(final AppUpdater.UpdateInfo info) {
        if (info == null || isFinishing()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("현재 버전: v").append(appUpdater.getLocalVersionName())
          .append(" (").append(appUpdater.getLocalVersionCode()).append(")\n")
          .append("새 버전: v").append(info.versionName);
        if (info.versionCode > 0) sb.append(" (").append(info.versionCode).append(")");
        if (info.apkSize > 0) {
            sb.append(String.format(Locale.US, "\n크기: %.2f MB", info.apkSize / (1024.0 * 1024.0)));
        }
        if (info.notes != null && !info.notes.isEmpty()) {
            sb.append("\n\n변경 내용:\n").append(info.notes);
        }
        new AlertDialog.Builder(this)
                .setTitle("HKG HUD Display 업데이트 가능")
                .setMessage(sb.toString())
                .setPositiveButton("지금 업데이트", (d, w) -> startUpdateDownloadAndInstall(info))
                .setNeutralButton("이 버전 건너뛰기", (d, w) -> {
                    prefs.edit().putString(KEY_DISMISSED_UPDATE_VER, info.versionName).apply();
                    applyImmersiveUi();
                })
                .setNegativeButton("나중에", (d, w) -> applyImmersiveUi())
                .show();
    }

    private void startUpdateDownloadAndInstall(final AppUpdater.UpdateInfo info) {
        if (appUpdater == null || info == null || isFinishing()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 24, 36, 16);

        final TextView tvStage = new TextView(this);
        tvStage.setText("GitHub에서 APK 다운로드 준비 중...");
        tvStage.setPadding(0, 0, 0, 14);
        box.addView(tvStage);

        final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        box.addView(pb);

        final AlertDialog progDlg = new AlertDialog.Builder(this)
                .setTitle("업데이트 다운로드 (v" + info.versionName + ")")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("취소", (d, w) -> {
                    appUpdater.cancelDownload();
                    applyImmersiveUi();
                })
                .create();
        progDlg.show();

        appUpdater.downloadApkAsync(info, new AppUpdater.DownloadCallback() {
            @Override
            public void onProgress(final long downloaded, final long total, final String stage) {
                uiHandler.post(() -> {
                    if (isFinishing() || !progDlg.isShowing()) return;
                    if (total > 0) {
                        int pct = (int) Math.max(0, Math.min(100, (downloaded * 100L) / total));
                        pb.setIndeterminate(false);
                        pb.setProgress(pct);
                        tvStage.setText(String.format(Locale.US, "%s (%d%% · %.2f / %.2f MB)",
                                stage, pct, downloaded / 1048576.0, total / 1048576.0));
                    } else {
                        pb.setIndeterminate(true);
                        tvStage.setText(String.format(Locale.US, "%s (%.2f MB)", stage, downloaded / 1048576.0));
                    }
                });
            }

            @Override
            public void onComplete(final boolean ok, final File apkFile, final String message) {
                uiHandler.post(() -> {
                    if (progDlg.isShowing()) progDlg.dismiss();
                    if (isFinishing()) return;
                    if (!ok || apkFile == null) {
                        Toast.makeText(MainActivity.this, message != null ? message : "다운로드 실패", Toast.LENGTH_LONG).show();
                        applyImmersiveUi();
                        return;
                    }
                    boolean started = appUpdater.startInstall(MainActivity.this, apkFile);
                    if (!started && Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        pendingPermissionInstall = true;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("앱 설치 권한 필요")
                                .setMessage("자동 업데이트 APK 설치를 위해 '이 출처의 앱 허용' 권한을 켜주세요.")
                                .setPositiveButton("권한 설정 열기", (d2, w2) -> {
                                    try {
                                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:" + getPackageName())));
                                    } catch (Throwable ignored) {
                                        Toast.makeText(MainActivity.this, "설정 화면을 열 수 없습니다", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("취소", (d2, w2) -> {
                                    pendingPermissionInstall = false;
                                    applyImmersiveUi();
                                })
                                .show();
                    }
                });
            }
        });
    }

    private void showSettingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 28;
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        HudState st = telemetryClient != null ? telemetryClient.snapshot() : new HudState();
        final TextView statusTv = new TextView(this);
        String verLine = appUpdater != null
                ? String.format(Locale.US, "\n앱 버전: v%s (%d) · %s",
                        appUpdater.getLocalVersionName(), appUpdater.getLocalVersionCode(), appUpdater.getStatusText())
                : "";
        statusTv.setText(String.format(
                "연결 상태: 헤드유닛 %s (%s, %.1f Hz) | 콤마 %s (%s, %.1f Hz)\n웹 테스트 콘솔: http://%s:%d%s",
                st.hubConnected ? "연결됨" : "대기중",
                st.hubIp != null && !st.hubIp.isEmpty() ? st.hubIp : "자동탐색",
                st.hubHz,
                st.commaDirectConnected ? "직결" : (st.commaConnected ? "경유" : "미연결"),
                st.commaIp != null && !st.commaIp.isEmpty() ? st.commaIp : "-",
                st.commaHz,
                HudDebugWebServer.getLocalIpv4(),
                HudDebugWebServer.PORT,
                verLine));
        statusTv.setPadding(0, 0, 0, 18);
        root.addView(statusTv);

        TextView modeLbl = new TextView(this);
        modeLbl.setText("표시 모드");
        root.addView(modeLbl);
        final RadioGroup rgMode = new RadioGroup(this);
        rgMode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbLive = new RadioButton(this); rbLive.setText("실시간"); rbLive.setId(1);
        RadioButton rbDemo = new RadioButton(this); rbDemo.setText("데모(주행)"); rbDemo.setId(2);
        RadioButton rbPark = new RadioButton(this); rbPark.setText("데모(주차)"); rbPark.setId(3);
        rgMode.addView(rbLive); rgMode.addView(rbDemo); rgMode.addView(rbPark);
        boolean curDemo = prefs.getBoolean(KEY_DEMO, false);
        boolean curPark = prefs.getBoolean(KEY_DEMO_PARK, false);
        rgMode.check(!curDemo ? 1 : (curPark ? 3 : 2));
        root.addView(rgMode);

        TextView hubLbl = new TextView(this);
        hubLbl.setText("헤드유닛 IP (비워두면 UDP :8767 자동탐색)");
        hubLbl.setPadding(0, 14, 0, 4);
        root.addView(hubLbl);
        final EditText etHub = new EditText(this);
        etHub.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etHub.setHint("자동탐색 또는 예: 192.168.43.10");
        etHub.setText(prefs.getString(KEY_HUB_IP, ""));
        root.addView(etHub);

        TextView commaLbl = new TextView(this);
        commaLbl.setText("콤마 3X IP (비워두면 헤드유닛 비콘 값 사용)");
        commaLbl.setPadding(0, 10, 0, 4);
        root.addView(commaLbl);
        final EditText etComma = new EditText(this);
        etComma.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etComma.setHint("자동 또는 예: 192.168.43.55");
        etComma.setText(prefs.getString(KEY_COMMA_IP, ""));
        root.addView(etComma);

        TextView themeLbl = new TextView(this);
        themeLbl.setText("화면 테마");
        themeLbl.setPadding(0, 14, 0, 4);
        root.addView(themeLbl);
        final RadioGroup rgTheme = new RadioGroup(this);
        rgTheme.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbTAuto = new RadioButton(this); rbTAuto.setText("자동(전조등)"); rbTAuto.setId(10);
        RadioButton rbTDark = new RadioButton(this); rbTDark.setText("다크"); rbTDark.setId(11);
        RadioButton rbTLight = new RadioButton(this); rbTLight.setText("라이트"); rbTLight.setId(12);
        rgTheme.addView(rbTAuto); rgTheme.addView(rbTDark); rgTheme.addView(rbTLight);
        int curTheme = prefs.getInt(KEY_THEME, HudState.THEME_AUTO);
        rgTheme.check(curTheme == HudState.THEME_DARK ? 11 : (curTheme == HudState.THEME_LIGHT ? 12 : 10));
        root.addView(rgTheme);

        TextView ptLbl = new TextView(this);
        ptLbl.setText("차종 표시");
        ptLbl.setPadding(0, 10, 0, 4);
        root.addView(ptLbl);
        final RadioGroup rgPt = new RadioGroup(this);
        rgPt.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbPAuto = new RadioButton(this); rbPAuto.setText("자동"); rbPAuto.setId(20);
        RadioButton rbPEv = new RadioButton(this); rbPEv.setText("EV"); rbPEv.setId(21);
        RadioButton rbPIce = new RadioButton(this); rbPIce.setText("ICE(내연)"); rbPIce.setId(22);
        rgPt.addView(rbPAuto); rgPt.addView(rbPEv); rgPt.addView(rbPIce);
        int curPt = prefs.getInt(KEY_PT, HudState.PT_AUTO);
        rgPt.check(curPt == HudState.PT_EV ? 21 : (curPt == HudState.PT_ICE ? 22 : 20));
        root.addView(rgPt);

        final CheckBox cbCommaDirect = new CheckBox(this);
        cbCommaDirect.setText("콤마 3X WebSocket 직결 (초저지연 3D 주행)");
        cbCommaDirect.setChecked(prefs.getBoolean(KEY_COMMA_DIRECT, true));
        root.addView(cbCommaDirect);

        final CheckBox cbBoot = new CheckBox(this);
        cbBoot.setText("부팅 시 자동 전체화면 실행");
        cbBoot.setChecked(prefs.getBoolean(KEY_AUTO_START, false));
        root.addView(cbBoot);

        final CheckBox cbStandby = new CheckBox(this);
        cbStandby.setText("시동 OFF 수신 시 절전 대기화면");
        cbStandby.setChecked(prefs.getBoolean(KEY_STANDBY, true));
        root.addView(cbStandby);

        final CheckBox cbPill = new CheckBox(this);
        cbPill.setText("상단 연결·FPS 배지 표시");
        cbPill.setChecked(prefs.getBoolean(KEY_STATUS_PILL, true));
        root.addView(cbPill);

        final CheckBox cbCar3d = new CheckBox(this);
        cbCar3d.setText("실시간 3D 니로 EV 모델 (OpenGL)");
        cbCar3d.setChecked(prefs.getBoolean(KEY_CAR3D, true));
        root.addView(cbCar3d);

        TextView colLbl = new TextView(this);
        colLbl.setText("3D 차체 색상");
        colLbl.setPadding(0, 10, 0, 4);
        root.addView(colLbl);
        final RadioGroup rgColor = new RadioGroup(this);
        rgColor.setOrientation(RadioGroup.HORIZONTAL);
        String[] colNames = {"실버", "그라파이트", "화이트", "블루", "블랙"};
        for (int i = 0; i < colNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(colNames[i]);
            rb.setId(30 + i);
            rgColor.addView(rb);
        }
        int curCol = Math.max(0, Math.min(4, prefs.getInt(KEY_CAR3D_COLOR, 0)));
        rgColor.check(30 + curCol);
        root.addView(rgColor);

        final CheckBox cbAutoUpdate = new CheckBox(this);
        cbAutoUpdate.setText("GitHub 자동 업데이트 확인 (주차 시 안내)");
        cbAutoUpdate.setChecked(prefs.getBoolean(KEY_AUTO_UPDATE, true));
        cbAutoUpdate.setPadding(0, 14, 0, 4);
        root.addView(cbAutoUpdate);

        TextView repoLbl = new TextView(this);
        repoLbl.setText("GitHub 업데이트 저장소 / 브랜치");
        repoLbl.setPadding(0, 8, 0, 4);
        root.addView(repoLbl);
        final EditText etUpdateRepo = new EditText(this);
        etUpdateRepo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etUpdateRepo.setHint(AppUpdater.DEFAULT_REPO);
        etUpdateRepo.setText(prefs.getString(KEY_UPDATE_REPO, AppUpdater.DEFAULT_REPO));
        root.addView(etUpdateRepo);

        final EditText etUpdateBranch = new EditText(this);
        etUpdateBranch.setInputType(InputType.TYPE_CLASS_TEXT);
        etUpdateBranch.setHint(AppUpdater.DEFAULT_BRANCH);
        etUpdateBranch.setText(prefs.getString(KEY_UPDATE_BRANCH, AppUpdater.DEFAULT_BRANCH));
        root.addView(etUpdateBranch);

        final Button btnCheckUpdate = new Button(this);
        AppUpdater.UpdateInfo curAvail = appUpdater != null ? appUpdater.getLatestAvailable() : null;
        btnCheckUpdate.setText(curAvail != null
                ? ("새 버전 v" + curAvail.versionName + " 다운로드 · 설치")
                : "GitHub 지금 업데이트 확인");
        btnCheckUpdate.setOnClickListener(v -> {
            if (appUpdater == null) return;
            String rSlug = etUpdateRepo.getText().toString().trim();
            String bName = etUpdateBranch.getText().toString().trim();
            if (rSlug.isEmpty()) rSlug = AppUpdater.DEFAULT_REPO;
            if (bName.isEmpty()) bName = AppUpdater.DEFAULT_BRANCH;
            prefs.edit().putString(KEY_UPDATE_REPO, rSlug).putString(KEY_UPDATE_BRANCH, bName).apply();
            appUpdater.configure(rSlug, bName);

            AppUpdater.UpdateInfo ready = appUpdater.getLatestAvailable();
            if (ready != null && !ready.apkUrl.isEmpty()) {
                showUpdateAvailableDialog(ready);
                return;
            }
            btnCheckUpdate.setEnabled(false);
            btnCheckUpdate.setText("GitHub 확인 중...");
            appUpdater.checkForUpdateAsync(null, (ok, found, msg) -> uiHandler.post(() -> {
                btnCheckUpdate.setEnabled(true);
                if (hudView != null) {
                    hudView.getRenderer().setAvailableUpdateVersion(found != null ? found.versionName : "");
                }
                if (found != null) {
                    btnCheckUpdate.setText("새 버전 v" + found.versionName + " 다운로드 · 설치");
                    showUpdateAvailableDialog(found);
                } else {
                    btnCheckUpdate.setText("GitHub 지금 업데이트 확인");
                    Toast.makeText(MainActivity.this, msg != null ? msg : "확인 완료", Toast.LENGTH_LONG).show();
                }
            }));
        });
        root.addView(btnCheckUpdate);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("HKG HUD Display 설정 (미디어 화면 탭)")
                .setView(scroll)
                .setPositiveButton("저장", (d, w) -> {
                    int mId = rgMode.getCheckedRadioButtonId();
                    boolean demo = mId != 1;
                    boolean park = mId == 3;
                    int tId = rgTheme.getCheckedRadioButtonId();
                    int t = tId == 11 ? HudState.THEME_DARK : (tId == 12 ? HudState.THEME_LIGHT : HudState.THEME_AUTO);
                    int pId = rgPt.getCheckedRadioButtonId();
                    int p = pId == 21 ? HudState.PT_EV : (pId == 22 ? HudState.PT_ICE : HudState.PT_AUTO);
                    int cId = rgColor.getCheckedRadioButtonId();
                    int c = (cId >= 30 && cId <= 34) ? (cId - 30) : 0;
                    String rSlug = etUpdateRepo.getText().toString().trim();
                    String bName = etUpdateBranch.getText().toString().trim();
                    if (rSlug.isEmpty()) rSlug = AppUpdater.DEFAULT_REPO;
                    if (bName.isEmpty()) bName = AppUpdater.DEFAULT_BRANCH;
                    prefs.edit()
                            .putBoolean(KEY_DEMO, demo)
                            .putBoolean(KEY_DEMO_PARK, park)
                            .putString(KEY_HUB_IP, etHub.getText().toString().trim())
                            .putString(KEY_COMMA_IP, etComma.getText().toString().trim())
                            .putInt(KEY_THEME, t)
                            .putInt(KEY_PT, p)
                            .putBoolean(KEY_COMMA_DIRECT, cbCommaDirect.isChecked())
                            .putBoolean(KEY_AUTO_START, cbBoot.isChecked())
                            .putBoolean(KEY_STANDBY, cbStandby.isChecked())
                            .putBoolean(KEY_STATUS_PILL, cbPill.isChecked())
                            .putBoolean(KEY_CAR3D, cbCar3d.isChecked())
                            .putInt(KEY_CAR3D_COLOR, c)
                            .putBoolean(KEY_AUTO_UPDATE, cbAutoUpdate.isChecked())
                            .putString(KEY_UPDATE_REPO, rSlug)
                            .putString(KEY_UPDATE_BRANCH, bName)
                            .apply();
                    manualModeActive = false;
                    if (debugWebServer != null) debugWebServer.setManualModeFlag(false);
                    applyConfigToRuntime();
                    applyImmersiveUi();
                })
                .setNegativeButton("닫기", (d, w) -> applyImmersiveUi())
                .create();
        dlg.show();
    }
}
