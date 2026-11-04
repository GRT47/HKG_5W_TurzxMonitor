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
NEMESIS_PID_FILE="/data/nemesis/pids/hkg.turzxmonitor+turzx-daemon"
NEMESIS_FAILS_FILE="/data/nemesis/pids/hkg.turzxmonitor+turzx-daemon.fails"
JAVA_PID_FILE="/data/local/tmp/hkg_turzxmonitor_java.pid"
BOOT_ID_FILE="/data/local/tmp/hkg_turzxmonitor_boot.id"
OTA_RELOAD_FILE="/data/local/tmp/hkg_turzxmonitor_ota_reload"
LOG_FILE="/data/local/tmp/hkg_turzxmonitor.log"
DEBUG_FILE="/data/local/tmp/hkg_turzxmonitor_debug.txt"
MONITOR_PID=""
SYNC_TICK=0
CRASH_STREAK=0

is_svc_pid_alive() {
    _pid="$1"
    [ -n "$_pid" ] || return 1
    kill -0 "$_pid" 2>/dev/null || return 1
    [ -r "/proc/$_pid/cmdline" ] || return 1
    (/system/bin/busybox tr '\000' ' ' < "/proc/$_pid/cmdline" 2>/dev/null || tr '\000' ' ' < "/proc/$_pid/cmdline" 2>/dev/null) | grep -q "turzx-daemon"
}

is_java_pid_alive() {
    _pid="$1"
    [ -n "$_pid" ] || return 1
    kill -0 "$_pid" 2>/dev/null || return 1
    [ -r "/proc/$_pid/cmdline" ] || return 1
    (/system/bin/busybox tr '\000' ' ' < "/proc/$_pid/cmdline" 2>/dev/null || tr '\000' ' ' < "/proc/$_pid/cmdline" 2>/dev/null) | grep -q "com.hkg.turzxmonitor.TurzxMonitorMain"
}

mkdir -p /data/nemesis/pids 2>/dev/null
rm -f "$NEMESIS_FAILS_FILE" "$INSTALL_DIR/bin/"*.tmp "$INSTALL_DIR/svc/"*.tmp "$INSTALL_DIR/config/"*.tmp 2>/dev/null

# Clear stale persistent PID files across cold boots using kernel boot_id
if [ -r "/proc/sys/kernel/random/boot_id" ]; then
    cur_boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    old_boot_id="$(cat "$BOOT_ID_FILE" 2>/dev/null)"
    if [ -n "$cur_boot_id" ] && [ "$cur_boot_id" != "$old_boot_id" ]; then
        rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
        echo "$cur_boot_id" > "$BOOT_ID_FILE" 2>/dev/null
    fi
fi

if [ -f "/data/nemesis/services.db" ] && grep -q "hkg.turzxmonitor.*|background" /data/nemesis/services.db 2>/dev/null; then
    sed 's/\(hkg\.turzxmonitor[^|]*|[^|]*|[^|]*|[^|]*|\)background/\1restart/g' /data/nemesis/services.db > /data/nemesis/services.db.tmp 2>/dev/null
    if [ -s "/data/nemesis/services.db.tmp" ]; then
        sync
        mv -f /data/nemesis/services.db.tmp /data/nemesis/services.db 2>/dev/null
        sync
    else
        rm -f /data/nemesis/services.db.tmp 2>/dev/null
    fi
fi

if [ -f "$SVC_PID_FILE" ]; then
    old_svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
    if [ -n "$old_svc_pid" ] && [ "$old_svc_pid" != "$$" ] && is_svc_pid_alive "$old_svc_pid"; then
        echo "$old_svc_pid" > "$NEMESIS_PID_FILE" 2>/dev/null
        exit 0
    fi
    rm -f "$SVC_PID_FILE" 2>/dev/null
fi
echo "$$" > "$SVC_PID_FILE" 2>/dev/null
echo "$$" > "$NEMESIS_PID_FILE" 2>/dev/null

log_info() {
    echo "[TurzxMonitor] $*"
    log -t HKGTurzxMonitor "$*" 2>/dev/null
}

is_turzx_usb_present() {
    for udev in /sys/bus/usb/devices/*; do
        if [ -f "$udev/idVendor" ] && [ -f "$udev/idProduct" ]; then
            vid="$(cat "$udev/idVendor" 2>/dev/null)"
            pid="$(cat "$udev/idProduct" 2>/dev/null)"
            if [ "$vid" = "1cbe" ] && { [ "$pid" = "0092" ] || [ "$pid" = "0088" ]; }; then
                return 0
            fi
        fi
    done
    return 1
}

is_other_usb_device_present() {
    for usb_mnt in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/usb0; do
        if [ -d "$usb_mnt" ] && grep -q " $usb_mnt " /proc/mounts 2>/dev/null; then
            return 0
        fi
    done
    for udev in /sys/bus/usb/devices/*; do
        if [ -f "$udev/idVendor" ]; then
            vid="$(cat "$udev/idVendor" 2>/dev/null)"
            if [ -n "$vid" ] && [ "$vid" != "1d6b" ] && [ "$vid" != "1cbe" ]; then
                return 0
            fi
        fi
    done
    return 1
}

nudge_usb_host_for_turzx() {
    if is_turzx_usb_present || is_other_usb_device_present; then
        return 0
    fi
    log_info "TURZX 1cbe:0092 not yet enumerated after cold boot; cycling DWC_OTG host VBUS"
    echo 1 > /sys/module/tcc_dwc_otg/parameters/vbus_control_enable 2>/dev/null
    echo off > /sys/devices/dwc_otg/vbus 2>/dev/null
    echo off > /sys/devices/ehci_tcc.0/vbus 2>/dev/null
    echo off > /sys/devices/ohci_tcc.0/vbus 2>/dev/null
    sleep 1 &
    wait $!
    echo host > /sys/devices/dwc_otg/drdmode 2>/dev/null
    echo on > /sys/devices/dwc_otg/phystatus 2>/dev/null
    echo on > /sys/devices/dwc_otg/vbus 2>/dev/null
    echo on > /sys/devices/ehci_tcc.0/vbus 2>/dev/null
    echo on > /sys/devices/ohci_tcc.0/vbus 2>/dev/null
    sleep 1 &
    wait $!
    chmod 666 /dev/bus/usb/*/* 2>/dev/null
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
    if [ -n "$MONITOR_PID" ] && is_java_pid_alive "$MONITOR_PID"; then
        return 0
    fi
    if [ -f "$JAVA_PID_FILE" ]; then
        saved_jpid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
        if [ -n "$saved_jpid" ] && is_java_pid_alive "$saved_jpid"; then
            MONITOR_PID="$saved_jpid"
            return 0
        fi
        rm -f "$JAVA_PID_FILE" 2>/dev/null
    fi
    kill_monitor_java
    JAR_FILE="$INSTALL_DIR/bin/turzxmonitor.jar"
    JAR_BAK="$INSTALL_DIR/bin/turzxmonitor.jar.bak"
    jar_sz=0
    if [ -f "$JAR_FILE" ]; then
        jar_sz="$(wc -c < "$JAR_FILE" 2>/dev/null)"
    fi
    if [ -z "$jar_sz" ] || [ "$jar_sz" -lt 32768 ] 2>/dev/null; then
        if [ -f "$JAR_BAK" ]; then
            log_info "Detected truncated/missing turzxmonitor.jar (size=$jar_sz); restoring from turzxmonitor.jar.bak"
            cp -f "$JAR_BAK" "$JAR_FILE" 2>/dev/null
            chmod 644 "$JAR_FILE" 2>/dev/null
            rm -f /data/dalvik-cache/*turzxmonitor.jar* /data/local/tmp/dalvik-cache/*turzxmonitor.jar* 2>/dev/null
            sync
        fi
    else
        cp -f "$JAR_FILE" "$JAR_BAK" 2>/dev/null
    fi
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
    rm -f "$SVC_PID_FILE" "$NEMESIS_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
    kill_monitor_java
    exit 0
}
trap 'on_stop' TERM INT
trap '' HUP

log_info "Service started (pid=$$)"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2 &
    wait $!
done

vbus_wait=0
while [ "$(getprop sys.boot.vbus)" = "0" ] && [ "$vbus_wait" -lt 4 ]; do
    vbus_wait=$((vbus_wait + 1))
    sleep 1 &
    wait $!
done

sleep 1 &
wait $!

nudge_usb_host_for_turzx
start_monitor_java
sync_logs_to_usb

while true; do
    echo "$$" > "$SVC_PID_FILE" 2>/dev/null
    echo "$$" > "$NEMESIS_PID_FILE" 2>/dev/null
    rm -f "$NEMESIS_FAILS_FILE" 2>/dev/null
    chmod 666 /dev/bus/usb/*/* 2>/dev/null
    if [ -f "$OTA_RELOAD_FILE" ]; then
        rm -f "$OTA_RELOAD_FILE" 2>/dev/null
        log_info "OTA reload requested; restarting daemon script and Java process"
        kill_monitor_java
        rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
        exec /system/bin/sh "$INSTALL_DIR/svc/turzx-daemon.sh"
    fi
    if [ -z "$MONITOR_PID" ] || ! is_java_pid_alive "$MONITOR_PID"; then
        CRASH_STREAK=$((CRASH_STREAK + 1))
        if [ "$CRASH_STREAK" -ge 1 ]; then
            rm -f /data/dalvik-cache/*turzxmonitor.jar* /data/local/tmp/dalvik-cache/*turzxmonitor.jar* 2>/dev/null
        fi
        if [ "$CRASH_STREAK" -ge 2 ] && [ -f "$INSTALL_DIR/bin/turzxmonitor.jar.bak" ]; then
            log_info "Java daemon exited repeatedly ($CRASH_STREAK); rolling back turzxmonitor.jar from .bak"
            cp -f "$INSTALL_DIR/bin/turzxmonitor.jar.bak" "$INSTALL_DIR/bin/turzxmonitor.jar" 2>/dev/null
            chmod 644 "$INSTALL_DIR/bin/turzxmonitor.jar" 2>/dev/null
            sync
        fi
        start_monitor_java
    else
        CRASH_STREAK=0
    fi
    SYNC_TICK=$((SYNC_TICK + 1))
    if [ "$SYNC_TICK" -ge 6 ]; then
        SYNC_TICK=0
        sync_logs_to_usb
    fi
    sleep 5 &
    wait $!
done

