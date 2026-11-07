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
BOOT_ID_FILE="/data/local/tmp/hkg_turzxmonitor_boot.id"
CMD_FILE="/data/local/tmp/hkg_turzxmonitor_cmd"

# Clear stale persistent PID files across cold boots using kernel boot_id
if [ -r "/proc/sys/kernel/random/boot_id" ]; then
    cur_boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    old_boot_id="$(cat "$BOOT_ID_FILE" 2>/dev/null)"
    if [ -n "$cur_boot_id" ] && [ "$cur_boot_id" != "$old_boot_id" ]; then
        rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
        echo "$cur_boot_id" > "$BOOT_ID_FILE" 2>/dev/null
    fi
fi

is_svc_pid_alive() {
    _pid="$1"
    [ -n "$_pid" ] && kill -0 "$_pid" 2>/dev/null && [ -d "/proc/$_pid" ]
}

is_java_pid_alive() {
    _pid="$1"
    [ -n "$_pid" ] && kill -0 "$_pid" 2>/dev/null && [ -d "/proc/$_pid" ]
}

svc_alive=0
if [ -f "$SVC_PID_FILE" ]; then
    svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
    if is_svc_pid_alive "$svc_pid"; then
        svc_alive=1
    else
        rm -f "$SVC_PID_FILE" 2>/dev/null
    fi
fi

java_alive=0
if [ -f "$JAVA_PID_FILE" ]; then
    java_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if is_java_pid_alive "$java_pid"; then
        java_alive=1
    else
        rm -f "$JAVA_PID_FILE" 2>/dev/null
    fi
fi

if [ -n "$1" ]; then
    echo "$1" > "$CMD_FILE" 2>/dev/null
elif [ "$java_alive" = "1" ]; then
    echo "toggle" > "$CMD_FILE" 2>/dev/null
else
    echo "show" > "$CMD_FILE" 2>/dev/null
fi

if [ "$svc_alive" = "0" ]; then
    # Start through NEMESIS svcmgr so it owns the daemon (restart + cold-boot autostart). A manual
    # "service start" also re-enables a service svcmgr had marked failed.
    if [ -x /system/xbin/nemesis ]; then
        /system/xbin/nemesis service start hkg.turzxmonitor/hkg-turzxmonitor >/dev/null 2>&1
    else
        /system/bin/sh "$INSTALL_DIR/svc/turzx-daemon.sh" </dev/null >>/data/local/tmp/hkg_turzxmonitor.log 2>&1 &
    fi
fi

exit 0

