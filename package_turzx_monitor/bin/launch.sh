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
CMD_FILE="/data/local/tmp/hkg_turzxmonitor_cmd"

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

if [ "$svc_alive" = "0" ] || [ "$java_alive" = "0" ]; then
    rm -f /data/nemesis/pids/hkg.turzxmonitor+turzx-daemon.fails 2>/dev/null
    /system/bin/sh "$INSTALL_DIR/svc/turzx-daemon.sh" </dev/null >>/data/local/tmp/hkg_turzxmonitor.log 2>&1 &
fi

exit 0

