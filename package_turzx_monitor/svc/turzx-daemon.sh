#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar

if [ -d "/data/nemesis/apps/hkg.turzxmonitor" ]; then
    INSTALL_DIR="/data/nemesis/apps/hkg.turzxmonitor"
else
    INSTALL_DIR="/data/nemesis/packages/hkg.turzxmonitor"
fi
mkdir -p /data/dalvik-cache 2>/dev/null

SVC_PID_FILE="/data/local/tmp/hkg_turzxmonitor_svc.pid"
JAVA_PID_FILE="/data/local/tmp/hkg_turzxmonitor_java.pid"
OTA_RELOAD_FILE="/data/local/tmp/hkg_turzxmonitor_ota_reload"
LOG_FILE="/data/local/tmp/hkg_turzxmonitor.log"
DEBUG_FILE="/data/local/tmp/hkg_turzxmonitor_debug.txt"
MONITOR_PID=""
SYNC_TICK=0

if [ -f "$SVC_PID_FILE" ]; then
    old_svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
    if [ -n "$old_svc_pid" ] && [ "$old_svc_pid" != "$$" ] && kill -0 "$old_svc_pid" 2>/dev/null; then
        exit 0
    fi
fi
echo "$$" > "$SVC_PID_FILE" 2>/dev/null

log_info() {
    echo "[TurzxMonitor] $*"
    log -t HKGTurzxMonitor "$*" 2>/dev/null
}

kill_monitor_java() {
    if [ -n "$MONITOR_PID" ]; then
        kill "$MONITOR_PID" >/dev/null 2>&1
        MONITOR_PID=""
    fi
    /system/bin/busybox pkill -f "com.hkg.turzxmonitor.TurzxMonitorMain daemon" >/dev/null 2>&1
    pkill -f "com.hkg.turzxmonitor.TurzxMonitorMain daemon" >/dev/null 2>&1
}

start_monitor_java() {
    chmod 666 /dev/bus/usb/*/* 2>/dev/null
    if [ -n "$MONITOR_PID" ] && kill -0 "$MONITOR_PID" 2>/dev/null; then
        return 0
    fi
    if [ -f "$JAVA_PID_FILE" ]; then
        saved_jpid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
        if [ -n "$saved_jpid" ] && kill -0 "$saved_jpid" 2>/dev/null; then
            MONITOR_PID="$saved_jpid"
            return 0
        fi
    fi
    kill_monitor_java
    LOG_FILE="/data/local/tmp/hkg_turzxmonitor.log"
    if [ -f "$LOG_FILE" ]; then
        log_sz="$(wc -c < "$LOG_FILE" 2>/dev/null)"
        if [ -n "$log_sz" ] && [ "$log_sz" -gt 262144 ] 2>/dev/null; then
            mv -f "$LOG_FILE" "${LOG_FILE}.old" 2>/dev/null
        fi
    fi
    EXTRA_CP=""
    for fjar in /system/framework/com.hkmc.ext.hcan.jar /system/framework/com.infobank.jar /system/framework/automotive-service.jar; do
        if [ -f "$fjar" ]; then
            EXTRA_CP="${EXTRA_CP}:${fjar}"
        fi
    done
    CLASSPATH="$INSTALL_DIR/bin/turzxmonitor.jar$EXTRA_CP" /system/bin/app_process /system/bin com.hkg.turzxmonitor.TurzxMonitorMain daemon "$INSTALL_DIR" >>"$LOG_FILE" 2>&1 &
    MONITOR_PID="$!"
    echo "$MONITOR_PID" > "$JAVA_PID_FILE" 2>/dev/null
    log_info "Started TurzxMonitorMain daemon (pid=$MONITOR_PID, dir=$INSTALL_DIR)"
}

sync_logs_to_usb() {
    for usb_mnt in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/usb0; do
        if [ -d "$usb_mnt" ] && grep -q " $usb_mnt " /proc/mounts 2>/dev/null; then
            usb_log_dir="$usb_mnt/HKG_TurzxMonitor_Log"
            mkdir -p "$usb_log_dir" 2>/dev/null
            [ -f "$LOG_FILE" ] && cp -f "$LOG_FILE" "$usb_log_dir/hkg_turzxmonitor.log" 2>/dev/null
            [ -f "${LOG_FILE}.old" ] && cp -f "${LOG_FILE}.old" "$usb_log_dir/hkg_turzxmonitor.log.old" 2>/dev/null
            [ -f "$DEBUG_FILE" ] && cp -f "$DEBUG_FILE" "$usb_log_dir/HKG_TurzxMonitor_Debug.txt" 2>/dev/null
            dmesg 2>/dev/null | grep -iE "(usb|1cbe|turzx|rndis|xhci|ehci)" | tail -n 120 > "$usb_log_dir/dmesg_usb.txt" 2>/dev/null
            for udev in /sys/bus/usb/devices/*; do
                if [ -f "$udev/idVendor" ]; then
                    vid="$(cat "$udev/idVendor" 2>/dev/null)"
                    pid="$(cat "$udev/idProduct" 2>/dev/null)"
                    prod="$(cat "$udev/product" 2>/dev/null)"
                    echo "$(basename "$udev"): ${vid}:${pid} (${prod})"
                fi
            done > "$usb_log_dir/usb_devices.txt" 2>/dev/null
            sync
            break
        fi
    done
}

on_stop() {
    log_info "Service stopped via NEMESIS Manager"
    sync_logs_to_usb
    rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
    kill_monitor_java
    exit 0
}
trap 'on_stop' TERM INT

log_info "Service started (pid=$$)"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2 &
    wait $!
done

sleep 1 &
wait $!

start_monitor_java
sync_logs_to_usb

while true; do
    chmod 666 /dev/bus/usb/*/* 2>/dev/null
    if [ -f "$OTA_RELOAD_FILE" ]; then
        rm -f "$OTA_RELOAD_FILE" 2>/dev/null
        log_info "OTA reload requested; restarting daemon script and Java process"
        kill_monitor_java
        rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
        exec /system/bin/sh "$INSTALL_DIR/svc/turzx-daemon.sh"
    fi
    if [ -z "$MONITOR_PID" ] || ! kill -0 "$MONITOR_PID" 2>/dev/null; then
        start_monitor_java
    fi
    SYNC_TICK=$((SYNC_TICK + 1))
    if [ "$SYNC_TICK" -ge 6 ]; then
        SYNC_TICK=0
        sync_logs_to_usb
    fi
    sleep 5 &
    wait $!
done

