#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

/system/bin/busybox pkill -f "com.hkg.turzxmonitor.TurzxMonitorMain" >/dev/null 2>&1
pkill -f "com.hkg.turzxmonitor.TurzxMonitorMain" >/dev/null 2>&1
rm -f /data/local/tmp/hkg_turzxmonitor_svc.pid /data/local/tmp/hkg_turzxmonitor_java.pid /data/local/tmp/hkg_turzxmonitor_cmd >/dev/null 2>&1
rm -f /data/nemesis/packages/hkg.turzxmonitor >/dev/null 2>&1
exit 0

