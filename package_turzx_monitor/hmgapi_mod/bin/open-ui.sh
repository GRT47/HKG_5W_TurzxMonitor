#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
if [ -f "/data/nemesis/apps/hkg.turzxmonitor/bin/launch.sh" ]; then
    exec /system/bin/sh /data/nemesis/apps/hkg.turzxmonitor/bin/launch.sh show
elif [ -f "/data/nemesis/packages/hkg.turzxmonitor/bin/launch.sh" ]; then
    exec /system/bin/sh /data/nemesis/packages/hkg.turzxmonitor/bin/launch.sh show
fi
exit 0
