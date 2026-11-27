package com.hkg.huddisplay;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks GitHub branch manifest (package_t88_hud/update.json) and GitHub Releases for newer APKs,
 * verifies SHA-256 and package archive metadata, and installs via native PackageInstaller.Session.
 */
public final class AppUpdater {

    public static final String DEFAULT_REPO = "GRT47/HKG_5W_TurzxMonitor";
    public static final String DEFAULT_BRANCH = "android-hud";
    public static final String MANIFEST_PATH = "package_t88_hud/update.json";

    private static final String TAG = "HudUpdater";
    private static final long MAX_APK_BYTES = 50L * 1024L * 1024L;
    private static final Pattern VER_CODE_PAT = Pattern.compile("versionCode\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA256_PAT = Pattern.compile("sha256\\s*[:=]\\s*([0-9a-fA-F]{64})", Pattern.CASE_INSENSITIVE);

    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final long apkSize;
        public final String sha256;
        public final String notes;
        public final String source;

        public UpdateInfo(int versionCode, String versionName, String apkUrl, long apkSize,
                          String sha256, String notes, String source) {
            this.versionCode = versionCode;
            this.versionName = versionName != null ? versionName : "";
            this.apkUrl = apkUrl != null ? apkUrl : "";
            this.apkSize = apkSize;
            this.sha256 = sha256 != null ? sha256.trim().toLowerCase(Locale.US) : "";
            this.notes = notes != null ? notes : "";
            this.source = source != null ? source : "";
        }
    }

    public interface CheckCallback {
        void onResult(boolean ok, UpdateInfo available, String message);
    }

    public interface DownloadCallback {
        void onProgress(long downloaded, long total, String stage);
        void onComplete(boolean ok, File apkFile, String message);
    }

    public interface InstallStatusListener {
        void onInstallStatus(int status, String message);
    }

    private static volatile AppUpdater activeInstance;
    private static volatile InstallStatusListener installStatusListener;

    private final Context context;
    private int localVersionCode = 100;
    private String localVersionName = "1.0.0";

    private volatile String repo = DEFAULT_REPO;
    private volatile String branch = DEFAULT_BRANCH;
    private volatile boolean checking = false;
    private volatile boolean downloading = false;
    private volatile int downloadPct = 0;
    private volatile String statusText = "대기 중";
    private volatile long lastCheckMs = 0L;
    private volatile UpdateInfo latestAvailable = null;
    private volatile File pendingInstallApk = null;
    private volatile boolean cancelRequested = false;

    public AppUpdater(Context ctx) {
        this.context = ctx.getApplicationContext();
        activeInstance = this;
        loadLocalVersion();
        this.statusText = "v" + localVersionName + " (확인 전)";
    }

    private void loadLocalVersion() {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pi != null) {
                localVersionCode = (Build.VERSION.SDK_INT >= 28) ? (int) pi.getLongVersionCode() : pi.versionCode;
                if (pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                    localVersionName = pi.versionName.trim();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void configure(String repoSlug, String branchName) {
        String r = repoSlug != null ? repoSlug.trim() : "";
        String b = branchName != null ? branchName.trim() : "";
        if (!r.isEmpty() && r.contains("/")) this.repo = r;
        else this.repo = DEFAULT_REPO;
        if (!b.isEmpty()) this.branch = b;
        else this.branch = DEFAULT_BRANCH;
    }

    public static void setInstallStatusListener(InstallStatusListener listener) {
        installStatusListener = listener;
    }

    public static void notifyInstallStatus(int status, String message) {
        AppUpdater inst = activeInstance;
        if (inst != null && message != null && !message.isEmpty()) {
            inst.statusText = message;
            if (status == PackageInstaller.STATUS_SUCCESS) {
                inst.latestAvailable = null;
                inst.pendingInstallApk = null;
            }
        }
        Log.i(TAG, "Install status=" + status + " msg=" + message);
        InstallStatusListener l = installStatusListener;
        if (l != null) {
            try {
                l.onInstallStatus(status, message);
            } catch (Throwable ignored) {
            }
        }
    }

    public int getLocalVersionCode() { return localVersionCode; }
    public String getLocalVersionName() { return localVersionName; }
    public String getRepo() { return repo; }
    public String getBranch() { return branch; }
    public boolean isChecking() { return checking; }
    public boolean isDownloading() { return downloading; }
    public String getStatusText() { return statusText; }
    public UpdateInfo getLatestAvailable() { return latestAvailable; }
    public File getPendingInstallApk() { return pendingInstallApk; }

    public void cancelDownload() {
        cancelRequested = true;
    }

    public JSONObject toStatusJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("versionCode", localVersionCode);
            j.put("versionName", localVersionName);
            j.put("repo", repo);
            j.put("branch", branch);
            j.put("checking", checking);
            j.put("downloading", downloading);
            j.put("progressPct", downloadPct);
            j.put("status", statusText);
            j.put("lastCheckMs", lastCheckMs);
            UpdateInfo avail = latestAvailable;
            if (avail != null) {
                JSONObject av = new JSONObject();
                av.put("versionCode", avail.versionCode);
                av.put("versionName", avail.versionName);
                av.put("apkUrl", avail.apkUrl);
                av.put("apkSize", avail.apkSize);
                av.put("sha256", avail.sha256);
                av.put("notes", avail.notes);
                av.put("source", avail.source);
                j.put("available", av);
            }
        } catch (Throwable ignored) {
        }
        return j;
    }

    public void checkForUpdateAsync(final String manifestUrlOverride, final CheckCallback cb) {
        if (checking) {
            if (cb != null) cb.onResult(false, latestAvailable, "이미 업데이트 확인 중입니다");
            return;
        }
        checking = true;
        statusText = "GitHub 확인 중...";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                UpdateInfo best = null;
                boolean anySourceOk = false;
                String errMsg = "GitHub 연결 실패 (네트워크 확인)";
                try {
                    String override = manifestUrlOverride != null ? manifestUrlOverride.trim() : "";
                    if (!override.isEmpty()) {
                        HttpResult res = httpGetBytes(override, 8000, 262144, false);
                        if (res.code == 200 && res.bytes != null) {
                            anySourceOk = true;
                            best = parseManifestJson(new String(res.bytes, StandardCharsets.UTF_8), "custom");
                        } else if (res.error != null) {
                            errMsg = "매니페스트 확인 실패: " + res.error;
                        } else {
                            errMsg = "매니페스트 HTTP " + res.code;
                        }
                    } else {
                        String curRepo = repo;
                        String curBranch = branch;
                        String rawUrl = "https://raw.githubusercontent.com/" + curRepo + "/" + curBranch + "/" + MANIFEST_PATH;
                        HttpResult rawRes = httpGetBytes(rawUrl, 8000, 262144, false);
                        if (rawRes.code == 200 && rawRes.bytes != null) {
                            anySourceOk = true;
                            UpdateInfo mInfo = parseManifestJson(new String(rawRes.bytes, StandardCharsets.UTF_8), "update.json");
                            if (mInfo != null && !mInfo.apkUrl.isEmpty()) {
                                best = mInfo;
                            }
                        } else if (rawRes.code == 404) {
                            anySourceOk = true;
                        }

                        String relUrl = "https://api.github.com/repos/" + curRepo + "/releases?per_page=15";
                        HttpResult relRes = httpGetBytes(relUrl, 9000, 1024 * 1024, true);
                        if (relRes.code == 200 && relRes.bytes != null) {
                            anySourceOk = true;
                            UpdateInfo rInfo = parseReleasesJson(new String(relRes.bytes, StandardCharsets.UTF_8));
                            if (rInfo != null && !rInfo.apkUrl.isEmpty()) {
                                if (best == null || isNewer(rInfo.versionCode, rInfo.versionName, best.versionCode, best.versionName)) {
                                    best = rInfo;
                                }
                            }
                        } else if (!anySourceOk) {
                            errMsg = (relRes.error != null) ? ("GitHub 오류: " + relRes.error) : ("GitHub HTTP " + relRes.code);
                        }
                    }

                    lastCheckMs = System.currentTimeMillis();
                    if (!anySourceOk) {
                        statusText = errMsg;
                        Log.w(TAG, errMsg);
                        if (cb != null) cb.onResult(false, null, errMsg);
                        return;
                    }

                    if (best != null && !best.apkUrl.isEmpty()
                            && isNewer(best.versionCode, best.versionName, localVersionCode, localVersionName)) {
                        latestAvailable = best;
                        statusText = "새 버전 v" + best.versionName + " 사용 가능";
                        Log.i(TAG, "Update available: " + best.versionName + " (" + best.versionCode + ") from " + best.source);
                        if (cb != null) cb.onResult(true, best, statusText);
                    } else {
                        latestAvailable = null;
                        statusText = "최신 버전 (v" + localVersionName + ")";
                        Log.i(TAG, "Up to date: v" + localVersionName + " (" + localVersionCode + ")");
                        if (cb != null) cb.onResult(true, null, statusText);
                    }
                } catch (Throwable e) {
                    String msg = "업데이트 확인 오류: " + e.getMessage();
                    statusText = msg;
                    Log.w(TAG, msg, e);
                    if (cb != null) cb.onResult(false, null, msg);
                } finally {
                    checking = false;
                }
            }
        }, "Hud-UpdateCheck");
        t.setDaemon(true);
        t.start();
    }

    public void downloadApkAsync(final UpdateInfo info, final DownloadCallback cb) {
        if (info == null || info.apkUrl == null || info.apkUrl.isEmpty()) {
            if (cb != null) cb.onComplete(false, null, "다운로드 URL이 없습니다");
            return;
        }
        if (downloading) {
            if (cb != null) cb.onComplete(false, null, "이미 다운로드 중입니다");
            return;
        }
        downloading = true;
        cancelRequested = false;
        downloadPct = 0;
        statusText = "v" + info.versionName + " 다운로드 중...";

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                File cacheDir = context.getCacheDir();
                File partFile = new File(cacheDir, "hud_update.apk.part");
                File outFile = new File(cacheDir, "hud_update.apk");
                HttpURLConnection conn = null;
                InputStream is = null;
                FileOutputStream fos = null;
                try {
                    if (partFile.exists()) partFile.delete();
                    conn = openFollowRedirects(info.apkUrl, 12000, 30000, false);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new IOException("HTTP " + code);
                    }
                    long total = conn.getContentLengthLong();
                    if (total <= 0 && info.apkSize > 0) total = info.apkSize;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("APK 크기 초과: " + total);
                    }

                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    is = conn.getInputStream();
                    fos = new FileOutputStream(partFile);
                    byte[] buf = new byte[16384];
                    byte[] magic = new byte[4];
                    int magicLen = 0;
                    long downloaded = 0L;
                    long lastNotifyMs = 0L;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (cancelRequested) {
                            throw new IOException("사용자 취소");
                        }
                        if (magicLen < 4) {
                            int copy = Math.min(4 - magicLen, n);
                            System.arraycopy(buf, 0, magic, magicLen, copy);
                            magicLen += copy;
                        }
                        downloaded += n;
                        if (downloaded > MAX_APK_BYTES) {
                            throw new IOException("APK 크기 제한 초과");
                        }
                        md.update(buf, 0, n);
                        fos.write(buf, 0, n);

                        long now = System.currentTimeMillis();
                        if (now - lastNotifyMs >= 120L) {
                            lastNotifyMs = now;
                            downloadPct = (total > 0) ? (int) Math.min(99L, (downloaded * 100L) / total) : 0;
                            if (cb != null) cb.onProgress(downloaded, total, "다운로드 중...");
                        }
                    }
                    fos.getFD().sync();
                    fos.close();
                    fos = null;

                    if (downloaded < 65536L || magicLen < 4
                            || magic[0] != 0x50 || magic[1] != 0x4B || magic[2] != 0x03 || magic[3] != 0x04) {
                        partFile.delete();
                        throw new IOException("유효한 APK(ZIP) 파일이 아닙니다");
                    }

                    byte[] digest = md.digest();
                    StringBuilder sb = new StringBuilder(64);
                    for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
                    String actualSha = sb.toString();
                    if (info.sha256.length() == 64 && !info.sha256.equalsIgnoreCase(actualSha)) {
                        partFile.delete();
                        throw new IOException("SHA-256 무결성 검증 실패");
                    }

                    if (outFile.exists()) outFile.delete();
                    if (!partFile.renameTo(outFile)) {
                        throw new IOException("임시 APK 파일 이름 변경 실패");
                    }

                    PackageInfo pkgInfo = context.getPackageManager().getPackageArchiveInfo(outFile.getAbsolutePath(), 0);
                    if (pkgInfo == null || !context.getPackageName().equals(pkgInfo.packageName)) {
                        outFile.delete();
                        throw new IOException("유효하지 않은 APK 패키지");
                    }

                    downloadPct = 100;
                    pendingInstallApk = outFile;
                    statusText = "다운로드 완료 · 설치 준비됨";
                    if (cb != null) {
                        cb.onProgress(downloaded, downloaded, "무결성 검증 완료");
                        cb.onComplete(true, outFile, "다운로드 및 무결성 검증 완료");
                    }
                } catch (Throwable e) {
                    if (partFile.exists()) partFile.delete();
                    String msg = e.getMessage() != null ? e.getMessage() : "다운로드 실패";
                    statusText = msg;
                    Log.w(TAG, "Download failed: " + msg, e);
                    if (cb != null) cb.onComplete(false, null, msg);
                } finally {
                    downloading = false;
                    if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
                    if (is != null) try { is.close(); } catch (Throwable ignored) {}
                    if (conn != null) conn.disconnect();
                }
            }
        }, "Hud-UpdateDownload");
        t.setDaemon(true);
        t.start();
    }

    public boolean startInstall(Activity activity, File apkFile) {
        if (apkFile == null || !apkFile.isFile()) {
            statusText = "설치할 APK 파일을 찾을 수 없습니다";
            return false;
        }
        pendingInstallApk = apkFile;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    statusText = "알 수 없는 앱 설치 권한 필요";
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            try (InputStream in = new FileInputStream(apkFile);
                 OutputStream out = session.openWrite("base.apk", 0, apkFile.length())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }

            Intent cbIntent = new Intent(context, UpdateInstallReceiver.class);
            cbIntent.setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0);
            PendingIntent piStatus = PendingIntent.getBroadcast(context, sessionId, cbIntent, piFlags);
            statusText = "설치 확인 요청 중...";
            session.commit(piStatus.getIntentSender());
            session.close();
            session = null;
            return true;
        } catch (Throwable e) {
            if (session != null) {
                try { session.abandon(); } catch (Throwable ignored) {}
            }
            String msg = "설치 세션 오류: " + e.getMessage();
            statusText = msg;
            Log.e(TAG, msg, e);
            notifyInstallStatus(PackageInstaller.STATUS_FAILURE, msg);
            return false;
        }
    }

    public static boolean isNewer(int rCode, String rName, int lCode, String lName) {
        if (rCode > 0 && lCode > 0 && rCode != lCode) {
            return rCode > lCode;
        }
        int[] ra = parseVersionParts(rName);
        int[] la = parseVersionParts(lName);
        int maxLen = Math.max(ra.length, la.length);
        for (int i = 0; i < maxLen; i++) {
            int rv = i < ra.length ? ra[i] : 0;
            int lv = i < la.length ? la[i] : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static int[] parseVersionParts(String ver) {
        if (ver == null) return new int[]{0};
        String s = ver.trim();
        if (s.startsWith("hud-v") || s.startsWith("hud-V")) s = s.substring(5);
        else if (s.startsWith("hud-")) s = s.substring(4);
        else if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] segs = s.split("\\.");
        int[] out = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            try {
                String digits = segs[i].replaceAll("[^0-9].*$", "");
                out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private UpdateInfo parseManifestJson(String json, String source) {
        try {
            JSONObject o = new JSONObject(json);
            String pkg = o.optString("package", context.getPackageName());
            if (!pkg.isEmpty() && !context.getPackageName().equals(pkg)) return null;
            int vCode = o.optInt("versionCode", 0);
            String vName = o.optString("versionName", "").trim();
            String url = o.optString("apkUrl", "").trim();
            long size = o.optLong("apkSize", 0L);
            String sha = o.optString("sha256", "").trim();
            String notes = o.optString("notes", "").trim();
            return new UpdateInfo(vCode, vName, url, size, sha, notes, source);
        } catch (Throwable e) {
            Log.w(TAG, "parseManifestJson error: " + e.getMessage());
            return null;
        }
    }

    private UpdateInfo parseReleasesJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            UpdateInfo best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.optJSONObject(i);
                if (rel == null || rel.optBoolean("draft", false)) continue;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets == null) continue;

                String apkUrl = "";
                long apkSize = 0L;
                String shaAssetUrl = "";
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.optJSONObject(j);
                    if (a == null) continue;
                    String aName = a.optString("name", "");
                    String low = aName.toLowerCase(Locale.US);
                    if (low.endsWith(".apk")) {
                        if (apkUrl.isEmpty() || low.contains("hkghuddisplay")) {
                            apkUrl = a.optString("browser_download_url", "");
                            apkSize = a.optLong("size", 0L);
                        }
                    } else if (low.endsWith(".sha256") && (low.contains("hkghuddisplay") || shaAssetUrl.isEmpty())) {
                        shaAssetUrl = a.optString("browser_download_url", "");
                    }
                }
                if (apkUrl.isEmpty()) continue;

                String tag = rel.optString("tag_name", "").trim();
                String vName = tag;
                if (vName.startsWith("hud-v") || vName.startsWith("hud-V")) vName = vName.substring(5);
                else if (vName.startsWith("v") || vName.startsWith("V")) vName = vName.substring(1);

                String body = rel.optString("body", "");
                int vCode = 0;
                Matcher cm = VER_CODE_PAT.matcher(body);
                if (cm.find()) {
                    try { vCode = Integer.parseInt(cm.group(1)); } catch (NumberFormatException ignored) {}
                }
                String sha = "";
                Matcher sm = SHA256_PAT.matcher(body);
                if (sm.find()) {
                    sha = sm.group(1);
                } else if (!shaAssetUrl.isEmpty()) {
                    HttpResult sr = httpGetBytes(shaAssetUrl, 5000, 1024, false);
                    if (sr.code == 200 && sr.bytes != null) {
                        String txt = new String(sr.bytes, StandardCharsets.UTF_8).trim();
                        String[] tok = txt.split("\\s+");
                        if (tok.length > 0 && tok[0].matches("[0-9a-fA-F]{64}")) sha = tok[0];
                    }
                }
                UpdateInfo cand = new UpdateInfo(vCode, vName, apkUrl, apkSize, sha, body.trim(), "github-release");
                if (best == null || isNewer(cand.versionCode, cand.versionName, best.versionCode, best.versionName)) {
                    best = cand;
                }
            }
            return best;
        } catch (Throwable e) {
            Log.w(TAG, "parseReleasesJson error: " + e.getMessage());
            return null;
        }
    }

    private static final class HttpResult {
        int code;
        byte[] bytes;
        String error;
    }

    private HttpResult httpGetBytes(String urlStr, int timeoutMs, int maxBytes, boolean isApi) {
        HttpResult r = new HttpResult();
        HttpURLConnection conn = null;
        try {
            conn = openFollowRedirects(urlStr, timeoutMs, timeoutMs, isApi);
            r.code = conn.getResponseCode();
            if (r.code == 200) {
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream(4096)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        if (baos.size() + n > maxBytes) throw new IOException("Response too large");
                        baos.write(buf, 0, n);
                    }
                    r.bytes = baos.toByteArray();
                }
            }
        } catch (Throwable e) {
            r.error = e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private HttpURLConnection openFollowRedirects(String urlStr, int connTimeoutMs, int readTimeoutMs, boolean isApi) throws IOException {
        URL cur = new URL(urlStr);
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection conn = (HttpURLConnection) cur.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "HKG-HUD-Display/" + localVersionName);
            if (isApi && "api.github.com".equalsIgnoreCase(cur.getHost())) {
                conn.setRequestProperty("Accept", "application/vnd.github+json");
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Empty redirect Location");
                }
                cur = new URL(cur, loc);
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }
}
