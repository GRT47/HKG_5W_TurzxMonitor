package com.hkg.huddisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;

/**
 * Handles native PackageInstaller session callbacks and auto-relaunches MainActivity after self-update.
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_RESULT = "com.hkg.huddisplay.INSTALL_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            cleanCachedApk(context);
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                context.startActivity(launch);
            } catch (Throwable ignored) {
            }
            return;
        }

        if (ACTION_INSTALL_RESULT.equals(action)) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            context.startActivity(confirm);
                            AppUpdater.notifyInstallStatus(status, "설치 확인 창을 열었습니다");
                        } catch (Throwable e) {
                            AppUpdater.notifyInstallStatus(PackageInstaller.STATUS_FAILURE,
                                    "설치 확인 창 실행 실패: " + e.getMessage());
                        }
                    } else {
                        AppUpdater.notifyInstallStatus(PackageInstaller.STATUS_FAILURE, "설치 확인 인텐트 없음");
                    }
                    break;
                }
                case PackageInstaller.STATUS_SUCCESS:
                    cleanCachedApk(context);
                    AppUpdater.notifyInstallStatus(status, "업데이트 설치 완료");
                    break;
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    AppUpdater.notifyInstallStatus(status, "서명 불일치로 덮어쓸 수 없습니다 (기존 앱 삭제 후 재설치 필요)");
                    break;
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    AppUpdater.notifyInstallStatus(status, "사용자가 업데이트 설치를 취소했습니다");
                    break;
                default:
                    AppUpdater.notifyInstallStatus(status,
                            "설치 실패 (" + status + (msg != null && !msg.isEmpty() ? ": " + msg : "") + ")");
                    break;
            }
        }
    }

    private static void cleanCachedApk(Context context) {
        try {
            File cache = context.getCacheDir();
            File f1 = new File(cache, "hud_update.apk");
            File f2 = new File(cache, "hud_update.apk.part");
            if (f1.exists()) f1.delete();
            if (f2.exists()) f2.delete();
        } catch (Throwable ignored) {
        }
    }
}
