#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

/system/bin/busybox pkill -f "com\.hkg\.turzxmonitor(\.TurzxMonitorMain|$)" >/dev/null 2>&1
pkill -f "com\.hkg\.turzxmonitor(\.TurzxMonitorMain|$)" >/dev/null 2>&1
rm -f /dev/hkg_tmp/hkg_turzxmonitor_* /dev/hkg_tmp/hkg_turzx_state.json* >/dev/null 2>&1
rm -f /data/local/tmp/hkg_turzxmonitor_* /data/local/tmp/hkg_turzx_state.json* >/dev/null 2>&1
rm -f /data/nemesis/packages/hkg.turzxmonitor >/dev/null 2>&1
exit 0

