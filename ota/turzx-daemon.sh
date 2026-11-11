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
# NEMESIS svcmgr owns this service as "<package-id>/<service name>" (NEMESIS.data [service.0] name=).
# Its pidfile is /data/nemesis/pids/<qname with / -> +>, written by svcmgr only (pid starttime boot-token).
# Never write it from here, and never run a second copy outside svcmgr: an instance that exits because
# another copy is alive counts as a crash, and after 5 of them svcmgr marks the service failed and skips
# it on every later cold boot.
NEMESIS_QNAME="hkg.turzxmonitor/hkg-turzxmonitor"
SVCMGR_PID_FILE="/data/nemesis/pids/hkg.turzxmonitor+hkg-turzxmonitor"
NEMESIS_CLI="/system/xbin/nemesis"
JAVA_PID_FILE="/data/local/tmp/hkg_turzxmonitor_java.pid"
BOOT_ID_FILE="/data/local/tmp/hkg_turzxmonitor_boot.id"
OTA_RELOAD_FILE="/data/local/tmp/hkg_turzxmonitor_ota_reload"
LOG_FILE="/data/local/tmp/hkg_turzxmonitor.log"
DEBUG_FILE="/data/local/tmp/hkg_turzxmonitor_debug.txt"
USB_SYNC_REQ_FILE="/data/local/tmp/hkg_turzxmonitor_usbsync_req"
MONITOR_PID=""

# Crash backoff: restart delay grows 0 -> 10 -> 30 -> 60 -> 120 -> 300s while the Java process keeps
# dying within STABLE_RUN_SECS of its start; a run longer than that resets the streak.
CRASH_STREAK=0
STABLE_RUN_SECS=120
RESTART_PENDING=0
NEXT_START_UP=0
JAVA_START_UP=0

# USB log sync happens only (a) on request (overlay button / web ?save=1 -> USB_SYNC_REQ_FILE) or
# (b) once when a USB stick is newly mounted. Mounts appearing within USB_SETTLE_SECS of daemon start
# or of a QuickBoot resume are treated as boot remounts, not insertions (no periodic/shutdown writes:
# FAT32 sticks get corrupted by writes interrupted at ignition-off).
USB_SETTLE_SECS=90
USB_SETTLE_UNTIL=0
LAST_USB_MNT=""
LAST_WALL=""
LAST_UP=""

is_svc_pid_alive() {
    _pid="$1"
    [ -n "$_pid" ] && kill -0 "$_pid" 2>/dev/null && [ -d "/proc/$_pid" ]
}

is_java_pid_alive() {
    _pid="$1"
    [ -n "$_pid" ] && kill -0 "$_pid" 2>/dev/null && [ -d "/proc/$_pid" ]
}

mkdir -p /data/nemesis/pids 2>/dev/null
rm -f /data/nemesis/pids/hkg.turzxmonitor+turzx-daemon /data/nemesis/pids/hkg.turzxmonitor+turzx-daemon.fails 2>/dev/null
rm -f "$INSTALL_DIR/bin/"*.tmp "$INSTALL_DIR/svc/"*.tmp "$INSTALL_DIR/config/"*.tmp 2>/dev/null

# Ensure .bak always mirrors a valid non-empty turzxmonitor.jar on startup / OTA reload
if [ -s "$INSTALL_DIR/bin/turzxmonitor.jar" ]; then
    cp -f "$INSTALL_DIR/bin/turzxmonitor.jar" "$INSTALL_DIR/bin/turzxmonitor.jar.bak" 2>/dev/null
    chmod 644 "$INSTALL_DIR/bin/turzxmonitor.jar.bak" 2>/dev/null
fi

# Clear stale persistent PID files across cold boots using kernel boot_id
if [ -r "/proc/sys/kernel/random/boot_id" ]; then
    cur_boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    old_boot_id="$(cat "$BOOT_ID_FILE" 2>/dev/null)"
    if [ -n "$cur_boot_id" ] && [ "$cur_boot_id" != "$old_boot_id" ]; then
        rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
        echo "$cur_boot_id" > "$BOOT_ID_FILE" 2>/dev/null
    fi
fi

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

svcmgr_pid() {
    _sp=""
    if [ -f "$SVCMGR_PID_FILE" ]; then
        read _sp _rest < "$SVCMGR_PID_FILE"
    fi
    echo "$_sp"
}

# svcmgr writes its pidfile right after fork(), so give it a moment before deciding.
is_svcmgr_managed() {
    _tries=0
    while [ "$_tries" -lt 10 ]; do
        [ "$(svcmgr_pid)" = "$$" ] && return 0
        _tries=$((_tries + 1))
        if [ -x "/system/bin/busybox" ]; then
            /system/bin/busybox usleep 200000 2>/dev/null || sleep 1
        else
            sleep 1
        fi
    done
    return 1
}

if is_svcmgr_managed; then
    MANAGED=1
else
    MANAGED=0
fi

if [ -f "$SVC_PID_FILE" ]; then
    old_svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
    if [ -n "$old_svc_pid" ] && [ "$old_svc_pid" != "$$" ] && is_svc_pid_alive "$old_svc_pid"; then
        if [ "$MANAGED" = "1" ]; then
            # A copy started outside svcmgr (older launch.sh/post.sh). Take over instead of exiting, which
            # svcmgr would count as a crash. SIGKILL so its TERM trap does not stop the Java process;
            # the Java pid file lets this instance adopt it without a HUD restart.
            kill -9 "$old_svc_pid" 2>/dev/null
            echo "[TurzxMonitor] Took over from unmanaged daemon pid=$old_svc_pid"
        else
            exit 0
        fi
    fi
    rm -f "$SVC_PID_FILE" 2>/dev/null
fi

# Started outside svcmgr (launch.sh fallback, OTA reload of an old unmanaged copy): hand over to svcmgr.
# A manual "service start" also gives a service that svcmgr marked failed one more run, and svcmgr clears
# the failure count once it stays up for 10s, so cold-boot autostart recovers by itself.
if [ "$MANAGED" = "0" ] && [ -x "$NEMESIS_CLI" ]; then
    echo "[TurzxMonitor] Not started by NEMESIS svcmgr (pid=$$); handing over to $NEMESIS_QNAME"
    "$NEMESIS_CLI" service start "$NEMESIS_QNAME" >/dev/null 2>&1
    _wait=0
    while [ "$_wait" -lt 10 ]; do
        _mp="$(svcmgr_pid)"
        if [ -n "$_mp" ] && [ "$_mp" != "$$" ] && is_svc_pid_alive "$_mp"; then
            echo "[TurzxMonitor] Handed over to svcmgr-managed pid=$_mp"
            exit 0
        fi
        _wait=$((_wait + 1))
        sleep 1
    done
    echo "[TurzxMonitor] svcmgr handover failed; continuing unmanaged"
fi
echo "$$" > "$SVC_PID_FILE" 2>/dev/null

log_info() {
    echo "[TurzxMonitor] $*"
    log -t HKGTurzxMonitor "$*" 2>/dev/null
}

uptime_s() {
    _up=""
    read _up _rest < /proc/uptime 2>/dev/null
    _up="${_up%%.*}"
    echo "${_up:-0}"
}

wall_s() {
    if [ -x "/system/bin/busybox" ]; then
        /system/bin/busybox date +%s 2>/dev/null
    else
        date +%s 2>/dev/null
    fi
}

current_usb_mount() {
    for usb_mnt in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/usb0; do
        if [ -d "$usb_mnt" ] && grep " $usb_mnt " /proc/mounts >/dev/null 2>&1; then
            echo "$usb_mnt"
            return 0
        fi
    done
    return 1
}

is_turzx_usb_present() {
    for udev in /sys/bus/usb/devices/*; do
        if [ -f "$udev/idVendor" ] && [ -f "$udev/idProduct" ]; then
            vid="$(cat "$udev/idVendor" 2>/dev/null)"
            pid="$(cat "$udev/idProduct" 2>/dev/null)"
            if [ "$vid" = "1cbe" ] && { [ "$pid" = "0092" ] || [ "$pid" = "0088" ] || [ "$pid" = "0123" ]; }; then
                return 0
            fi
        fi
    done
    return 1
}

is_other_usb_device_present() {
    for usb_mnt in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/usb0; do
        if [ -d "$usb_mnt" ] && grep " $usb_mnt " /proc/mounts >/dev/null 2>&1; then
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
    log_info "TURZX 1cbe:(0092/0088/0123) not yet enumerated after cold boot; cycling DWC_OTG host VBUS"
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
    if [ ! -s "$JAR_FILE" ]; then
        if [ -s "$JAR_BAK" ]; then
            log_info "Detected missing/empty turzxmonitor.jar; restoring from turzxmonitor.jar.bak"
            cp -f "$JAR_BAK" "$JAR_FILE" 2>/dev/null
            chmod 644 "$JAR_FILE" 2>/dev/null
            rm -f /data/dalvik-cache/*turzxmonitor.jar* /data/local/tmp/dalvik-cache/*turzxmonitor.jar* 2>/dev/null
            sync
        fi
    else
        cp -f "$JAR_FILE" "$JAR_BAK" 2>/dev/null
        chmod 644 "$JAR_BAK" 2>/dev/null
    fi
    LOG_FILE="/data/local/tmp/hkg_turzxmonitor.log"
    if [ -f "$LOG_FILE" ] && [ -x "/system/bin/busybox" ]; then
        log_sz="$(/system/bin/busybox wc -c < "$LOG_FILE" 2>/dev/null)"
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
    for usb_mnt in "$1"; do
        if [ -n "$usb_mnt" ] && [ -d "$usb_mnt" ] && grep " $usb_mnt " /proc/mounts >/dev/null 2>&1; then
            usb_log_dir="$usb_mnt/HKG_TurzxMonitor_Log"
            mkdir -p "$usb_log_dir" 2>/dev/null
            [ -f "$LOG_FILE" ] && cp -f "$LOG_FILE" "$usb_log_dir/hkg_turzxmonitor.log" 2>/dev/null
            [ -f "${LOG_FILE}.old" ] && cp -f "${LOG_FILE}.old" "$usb_log_dir/hkg_turzxmonitor.log.old" 2>/dev/null
            [ -f "$DEBUG_FILE" ] && cp -f "$DEBUG_FILE" "$usb_log_dir/HKG_TurzxMonitor_Debug.txt" 2>/dev/null
            if [ -x "/system/bin/busybox" ]; then
                dmesg 2>/dev/null | /system/bin/busybox grep -iE "(usb|1cbe|turzx|rndis|xhci|ehci)" | /system/bin/busybox tail -n 120 > "$usb_log_dir/dmesg_usb.txt" 2>/dev/null
            else
                dmesg 2>/dev/null > "$usb_log_dir/dmesg_usb.txt" 2>/dev/null
            fi
            for udev in /sys/bus/usb/devices/*; do
                if [ -f "$udev/idVendor" ]; then
                    vid="$(cat "$udev/idVendor" 2>/dev/null)"
                    pid="$(cat "$udev/idProduct" 2>/dev/null)"
                    prod="$(cat "$udev/product" 2>/dev/null)"
                    echo "${udev##*/}: ${vid}:${pid} (${prod})"
                fi
            done > "$usb_log_dir/usb_devices.txt" 2>/dev/null
            sync
            break
        fi
    done
}

check_usb_log_sync() {
    now_up="$(uptime_s)"
    now_wall="$(wall_s)"
    # QuickBoot resume (or a large clock step): wall clock moved far more than monotonic uptime.
    if [ -n "$now_wall" ] && [ -n "$LAST_WALL" ] && [ -n "$LAST_UP" ]; then
        clock_gap=$(( (now_wall - LAST_WALL) - (now_up - LAST_UP) ))
        if [ "$clock_gap" -gt 30 ] || [ "$clock_gap" -lt -30 ]; then
            USB_SETTLE_UNTIL=$((now_up + USB_SETTLE_SECS))
            log_info "Resume/clock step detected (gap=${clock_gap}s); ignoring USB remounts for ${USB_SETTLE_SECS}s"
        fi
    fi
    LAST_WALL="$now_wall"
    LAST_UP="$now_up"

    cur_mnt="$(current_usb_mount)"
    if [ -f "$USB_SYNC_REQ_FILE" ]; then
        rm -f "$USB_SYNC_REQ_FILE" 2>/dev/null
        if [ -n "$cur_mnt" ]; then
            log_info "USB log sync requested (button/web) -> $cur_mnt"
            sync_logs_to_usb "$cur_mnt"
        else
            log_info "USB log sync requested but no USB storage mounted"
        fi
    elif [ -n "$cur_mnt" ] && [ "$cur_mnt" != "$LAST_USB_MNT" ]; then
        if [ "$now_up" -ge "$USB_SETTLE_UNTIL" ]; then
            log_info "USB storage inserted ($cur_mnt); syncing logs once"
            sync_logs_to_usb "$cur_mnt"
        else
            log_info "USB storage mounted during boot/resume settle window ($cur_mnt); not syncing"
        fi
    fi
    LAST_USB_MNT="$cur_mnt"
}

handle_java_exit() {
    now_up="$(uptime_s)"
    if [ "$RESTART_PENDING" = "0" ]; then
        alive_for=$((now_up - JAVA_START_UP))
        if [ "$alive_for" -ge "$STABLE_RUN_SECS" ]; then
            CRASH_STREAK=0
        fi
        CRASH_STREAK=$((CRASH_STREAK + 1))
        case "$CRASH_STREAK" in
            1) RESTART_DELAY=0 ;;
            2) RESTART_DELAY=10 ;;
            3) RESTART_DELAY=30 ;;
            4) RESTART_DELAY=60 ;;
            5) RESTART_DELAY=120 ;;
            *) RESTART_DELAY=300 ;;
        esac
        if [ "$CRASH_STREAK" -eq 2 ]; then
            # Only once per crash streak: a stale/corrupt odex is a plausible cause of an immediate re-crash,
            # but wiping on every restart forces a full dexopt each time.
            log_info "Clearing turzxmonitor Dalvik cache (crash streak=$CRASH_STREAK)"
            rm -f /data/dalvik-cache/*turzxmonitor.jar* /data/local/tmp/dalvik-cache/*turzxmonitor.jar* 2>/dev/null
        fi
        NEXT_START_UP=$((now_up + RESTART_DELAY))
        RESTART_PENDING=1
        log_info "Java process exited after ${alive_for}s (streak=$CRASH_STREAK); restarting in ${RESTART_DELAY}s"
    fi
    if [ "$now_up" -ge "$NEXT_START_UP" ]; then
        RESTART_PENDING=0
        start_monitor_java
        JAVA_START_UP="$(uptime_s)"
    fi
}

on_stop() {
    log_info "Service stopped via NEMESIS Manager"
    rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
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
JAVA_START_UP="$(uptime_s)"

# Baseline: a stick already mounted (or mounted during the settle window) is not an "insertion".
LAST_USB_MNT="$(current_usb_mount)"
LAST_UP="$(uptime_s)"
LAST_WALL="$(wall_s)"
USB_SETTLE_UNTIL=$((LAST_UP + USB_SETTLE_SECS))

while true; do
    echo "$$" > "$SVC_PID_FILE" 2>/dev/null
    chmod 666 /dev/bus/usb/*/* 2>/dev/null
    if [ -f "$OTA_RELOAD_FILE" ]; then
        rm -f "$OTA_RELOAD_FILE" 2>/dev/null
        log_info "OTA reload requested; restarting daemon script and Java process"
        if [ -s "$INSTALL_DIR/bin/turzxmonitor.jar" ]; then
            cp -f "$INSTALL_DIR/bin/turzxmonitor.jar" "$INSTALL_DIR/bin/turzxmonitor.jar.bak" 2>/dev/null
            chmod 644 "$INSTALL_DIR/bin/turzxmonitor.jar.bak" 2>/dev/null
        fi
        rm -f /data/dalvik-cache/*turzxmonitor.jar* /data/local/tmp/dalvik-cache/*turzxmonitor.jar* 2>/dev/null
        kill_monitor_java
        rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
        exec /system/bin/sh "$INSTALL_DIR/svc/turzx-daemon.sh"
    fi
    if [ -z "$MONITOR_PID" ] || ! is_java_pid_alive "$MONITOR_PID"; then
        handle_java_exit
    fi
    check_usb_log_sync
    sleep 5 &
    wait $!
done

