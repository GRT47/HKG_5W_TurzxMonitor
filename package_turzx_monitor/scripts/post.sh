#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data

if [ -d "/data/nemesis/apps/hkg.turzxmonitor" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.turzxmonitor"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.turzxmonitor"
fi

mkdir -p /data/nemesis/packages 2>/dev/null
if [ "$INSTALL_DIR" = "/data/nemesis/apps/hkg.turzxmonitor" ]; then
    rm -f /data/nemesis/packages/hkg.turzxmonitor 2>/dev/null
    ln -s "$INSTALL_DIR" /data/nemesis/packages/hkg.turzxmonitor 2>/dev/null
fi

chmod 755 "$INSTALL_DIR/bin/launch.sh" "$INSTALL_DIR/svc/turzx-daemon.sh" 2>/dev/null
chmod 644 "$INSTALL_DIR/bin/turzxmonitor.jar" "$INSTALL_DIR/NEMESIS.data" "$INSTALL_DIR/config/monitor.conf" "$INSTALL_DIR/config/version.json" "$INSTALL_DIR/web/turzx_92_mockup.html" 2>/dev/null
mkdir -p "$INSTALL_DIR/logs" /data/dalvik-cache /data/local/tmp/dalvik-cache 2>/dev/null

# Sync .bak with the newly installed JAR and clear stale Dalvik DEX caches
if [ -s "$INSTALL_DIR/bin/turzxmonitor.jar" ]; then
    cp -f "$INSTALL_DIR/bin/turzxmonitor.jar" "$INSTALL_DIR/bin/turzxmonitor.jar.bak" 2>/dev/null
    chmod 644 "$INSTALL_DIR/bin/turzxmonitor.jar.bak" 2>/dev/null
fi
rm -f "$INSTALL_DIR/bin/"*.tmp "$INSTALL_DIR/svc/"*.tmp "$INSTALL_DIR/config/"*.tmp 2>/dev/null
rm -f /data/dalvik-cache/*turzxmonitor.jar* /data/local/tmp/dalvik-cache/*turzxmonitor.jar* 2>/dev/null

for usb_mnt in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/usb0; do
    if [ -d "$usb_mnt" ] && grep " $usb_mnt " /proc/mounts >/dev/null 2>&1; then
        mkdir -p "$usb_mnt/HKG_TurzxMonitor_Log" 2>/dev/null
        echo "[$(date)] Installed/Updated hkg.turzxmonitor at $INSTALL_DIR" >> "$usb_mnt/HKG_TurzxMonitor_Log/install_history.txt" 2>/dev/null
        sync
        break
    fi
done

# Ensure NEMESIS svcmgr monitors turzx-daemon in 'restart' mode across Warm Boot / QuickBoot cycles
mkdir -p /data/nemesis/pids 2>/dev/null
rm -f /data/nemesis/pids/hkg.turzxmonitor+turzx-daemon /data/nemesis/pids/hkg.turzxmonitor+turzx-daemon.fails 2>/dev/null
if [ -f "/data/nemesis/services.db" ] && grep "hkg.turzxmonitor.*|background" /data/nemesis/services.db >/dev/null 2>&1; then
    if [ -x "/system/bin/busybox" ]; then
        /system/bin/busybox sed 's/\(hkg\.turzxmonitor[^|]*|[^|]*|[^|]*|[^|]*|\)background/\1restart/g' /data/nemesis/services.db > /data/nemesis/services.db.tmp 2>/dev/null
    else
        sed 's/\(hkg\.turzxmonitor[^|]*|[^|]*|[^|]*|[^|]*|\)background/\1restart/g' /data/nemesis/services.db > /data/nemesis/services.db.tmp 2>/dev/null
    fi
    if [ -s "/data/nemesis/services.db.tmp" ]; then
        sync
        mv -f /data/nemesis/services.db.tmp /data/nemesis/services.db 2>/dev/null
        sync
    else
        rm -f /data/nemesis/services.db.tmp 2>/dev/null
    fi
fi

# Stop any running previous version so the newly installed version takes effect immediately
for pid_file in /data/local/tmp/hkg_turzxmonitor_java.pid /data/local/tmp/hkg_turzxmonitor_svc.pid; do
    if [ -f "$pid_file" ]; then
        old_pid="$(cat "$pid_file" 2>/dev/null)"
        if [ -n "$old_pid" ]; then
            kill "$old_pid" >/dev/null 2>&1
        fi
    fi
done
/system/bin/busybox pkill -f "com.hkg.turzxmonitor.TurzxMonitorMain" >/dev/null 2>&1
/system/bin/busybox pkill -f "turzx-daemon.sh" >/dev/null 2>&1
rm -f /data/local/tmp/hkg_turzxmonitor_svc.pid /data/local/tmp/hkg_turzxmonitor_java.pid /data/local/tmp/hkg_turzxmonitor_ota_reload 2>/dev/null
sync

# Show control/preview overlay immediately after installation.
# The daemon itself is started by NEMESIS (svcmgr) right after this hook returns; starting a copy here
# made the svcmgr copy exit as a duplicate 5 times -> service marked failed -> no cold-boot autostart.
echo "show" > /data/local/tmp/hkg_turzxmonitor_cmd 2>/dev/null

exit 0

