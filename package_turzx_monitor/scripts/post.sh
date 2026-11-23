#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
if [ -z "$BOOTCLASSPATH" ]; then
    export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
fi

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

chmod 755 "$INSTALL_DIR/bin/launch.sh" "$INSTALL_DIR/svc/turzx-daemon.sh" "$INSTALL_DIR/hmgapi_mod/bin/open-ui.sh" 2>/dev/null
chmod 644 "$INSTALL_DIR/bin/turzxmonitor.jar" "$INSTALL_DIR/NEMESIS.data" "$INSTALL_DIR/config/monitor.conf" "$INSTALL_DIR/config/version.json" "$INSTALL_DIR/web/turzx_92_mockup.html" "$INSTALL_DIR/hmgapi_mod/mod.json" "$INSTALL_DIR/hmgapi_mod/HkgTurzxShortcut.apk" "$INSTALL_DIR/hmgapi_mod/"*.png 2>/dev/null
mkdir -p "$INSTALL_DIR/logs" /data/dalvik-cache /data/local/tmp/dalvik-cache /dev/hkg_tmp 2>/dev/null
chmod 777 /dev/hkg_tmp 2>/dev/null

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
for pid_file in /dev/hkg_tmp/hkg_turzxmonitor_java.pid /dev/hkg_tmp/hkg_turzxmonitor_svc.pid /data/local/tmp/hkg_turzxmonitor_java.pid /data/local/tmp/hkg_turzxmonitor_svc.pid; do
    if [ -f "$pid_file" ]; then
        old_pid="$(cat "$pid_file" 2>/dev/null)"
        if [ -n "$old_pid" ]; then
            kill "$old_pid" >/dev/null 2>&1
        fi
    fi
done
/system/bin/busybox pkill -f "com\.hkg\.turzxmonitor(\.TurzxMonitorMain|$)" >/dev/null 2>&1
pkill -f "com\.hkg\.turzxmonitor(\.TurzxMonitorMain|$)" >/dev/null 2>&1
/system/bin/busybox pkill -f "turzx-daemon.sh" >/dev/null 2>&1
rm -f /dev/hkg_tmp/hkg_turzxmonitor_svc.pid /dev/hkg_tmp/hkg_turzxmonitor_java.pid /dev/hkg_tmp/hkg_turzxmonitor_ota_reload 2>/dev/null
rm -f /data/local/tmp/hkg_turzxmonitor_svc.pid /data/local/tmp/hkg_turzxmonitor_java.pid /data/local/tmp/hkg_turzxmonitor_ota_reload /data/local/tmp/hkg_turzx_state.json /data/local/tmp/hkg_turzx_state.json.tmp 2>/dev/null
sync

# Register all-apps menu & star-key shortcut mod if HMG-API is installed on this head unit
if [ -f "/system/framework/hmgapi-hook.jar" ] && [ -d "/data/hmgapi" ] && [ -d "$INSTALL_DIR/hmgapi_mod" ]; then
    HMG_LOG="/dev/hkg_tmp/hkg_turzxmonitor_hmgapi.log"
    MOD_KEY_DIR="/data/hmgapi/local-keys"
    MOD_KEY="$MOD_KEY_DIR/hkg-turzxmonitor.key"
    STAGE_ROOT="/dev/hkg_tmp/hkg_turzx_mod_stage"
    STAGE_MOD="$STAGE_ROOT/hkg-turzxmonitor"
    echo "=== hkg.turzxmonitor HMG-API sync $(date 2>/dev/null) ===" > "$HMG_LOG" 2>/dev/null
    if [ -x /system/bin/hmgapid ]; then
        /system/bin/hmgapid ping >/dev/null 2>&1 || /system/bin/hmgapid daemon </dev/null >>"$HMG_LOG" 2>&1 &
    fi
    mkdir -p "$MOD_KEY_DIR" 2>/dev/null
    chmod 700 "$MOD_KEY_DIR" 2>/dev/null
    if [ ! -s "$MOD_KEY" ]; then
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Signing keygen "$MOD_KEY" >>"$HMG_LOG" 2>&1
    fi
    rm -rf "$STAGE_ROOT" 2>/dev/null
    mkdir -p "$STAGE_MOD/bin" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/mod.json" "$STAGE_MOD/mod.json" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/HkgTurzxShortcut.apk" "$STAGE_MOD/HkgTurzxShortcut.apk" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/"*.png "$STAGE_MOD/" 2>/dev/null
    cp -f "$INSTALL_DIR/hmgapi_mod/bin/open-ui.sh" "$STAGE_MOD/bin/open-ui.sh" 2>/dev/null
    chmod 755 "$STAGE_MOD/bin/open-ui.sh" 2>/dev/null

    CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Signing sign "$STAGE_MOD" "$MOD_KEY" >>"$HMG_LOG" 2>&1

    case "$(pm path com.hkg.turzxmonitor.app 2>/dev/null)" in
        package:*)
            pm uninstall com.hkg.turzxmonitor.app >>"$HMG_LOG" 2>&1
            ;;
    esac

    if ! CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine install "$STAGE_MOD" --from-nemesis --force >>"$HMG_LOG" 2>&1; then
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine remove hkg-turzxmonitor --from-nemesis >>"$HMG_LOG" 2>&1
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine install "$STAGE_MOD" --from-nemesis --force >>"$HMG_LOG" 2>&1
    fi

    if [ -x /system/bin/hmgapid ]; then
        /system/bin/hmgapid engine apply >>"$HMG_LOG" 2>&1
    else
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine apps >>"$HMG_LOG" 2>&1
        CLASSPATH=/system/framework/hmgapi-hook.jar /system/bin/app_process /system/bin hmgapi.engine.Engine publish-state >>"$HMG_LOG" 2>&1
    fi
    rm -rf "$STAGE_ROOT" 2>/dev/null
fi

# Show control/preview overlay immediately after installation.
# The daemon itself is started by NEMESIS (svcmgr) right after this hook returns; starting a copy here
# made the svcmgr copy exit as a duplicate 5 times -> service marked failed -> no cold-boot autostart.
echo "show" > /dev/hkg_tmp/hkg_turzxmonitor_cmd 2>/dev/null

exit 0

