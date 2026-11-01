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

for usb_mnt in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/usb0; do
    if [ -d "$usb_mnt" ] && grep -q " $usb_mnt " /proc/mounts 2>/dev/null; then
        mkdir -p "$usb_mnt/HKG_TurzxMonitor_Log" 2>/dev/null
        echo "[$(date)] Installed/Updated hkg.turzxmonitor at $INSTALL_DIR" >> "$usb_mnt/HKG_TurzxMonitor_Log/install_history.txt" 2>/dev/null
        sync
        break
    fi
done

# Show control/preview overlay immediately after installation
echo "show" > /data/local/tmp/hkg_turzxmonitor_cmd 2>/dev/null

exit 0

