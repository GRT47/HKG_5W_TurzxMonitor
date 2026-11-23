#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
if [ -z "$BOOTCLASSPATH" ]; then
    export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
fi

/system/bin/busybox pkill -f "com\.hkg\.turzxmonitor(\.TurzxMonitorMain|$)" >/dev/null 2>&1
pkill -f "com\.hkg\.turzxmonitor(\.TurzxMonitorMain|$)" >/dev/null 2>&1

is_update=0
STAGE_DATA="/data/local/tmp/nemesis/stage/hkg.turzxmonitor/NEMESIS.data"
caller="$(cat /proc/$PPID/cmdline 2>/dev/null)"
case "$caller" in
    *uninstall*) ;;
    *)
        if grep -q "^id=hkg\.turzxmonitor$" "$STAGE_DATA" 2>/dev/null; then
            is_update=1
        fi ;;
esac
if [ "$is_update" = "0" ] && [ -f "/system/framework/hmgapi-hook.jar" ] && [ -d "/data/hmgapi/mods/hkg-turzxmonitor" ]; then
    CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine remove hkg-turzxmonitor --from-nemesis >/dev/null 2>&1
    if [ -x /system/bin/hmgapid ]; then
        /system/bin/hmgapid engine apply >/dev/null 2>&1
    fi
    pm uninstall com.hkg.turzxmonitor.app >/dev/null 2>&1
fi

rm -f /dev/hkg_tmp/hkg_turzxmonitor_* /dev/hkg_tmp/hkg_turzx_state.json* >/dev/null 2>&1
rm -f /data/local/tmp/hkg_turzxmonitor_* /data/local/tmp/hkg_turzx_state.json* >/dev/null 2>&1
rm -f /data/nemesis/packages/hkg.turzxmonitor >/dev/null 2>&1
exit 0

