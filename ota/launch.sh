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

svc_alive=0
if [ -f "$SVC_PID_FILE" ]; then
    svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
    if [ -n "$svc_pid" ] && kill -0 "$svc_pid" 2>/dev/null; then
        svc_alive=1
    fi
fi

java_alive=0
if [ -f "$JAVA_PID_FILE" ]; then
    java_pid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
    if [ -n "$java_pid" ] && kill -0 "$java_pid" 2>/dev/null; then
        java_alive=1
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

