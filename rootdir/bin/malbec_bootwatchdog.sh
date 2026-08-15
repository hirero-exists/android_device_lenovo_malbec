#!/system/bin/sh

OUT=/mnt/sd/malbec_bootdump.txt

dump_state() {
    {
        echo "=== MALBEC DUMP $1 $(date) ==="
        echo "--- getprop ---"
        getprop
        echo "--- dmesg ---"
        dmesg
        echo "--- logcat ---"
        logcat -b all -d
        echo "--- ps ---"
        ps -A
        echo "--- mounts ---"
        cat /proc/mounts
    } >> "$OUT" 2>&1
}

mount_sd() {
    [ -d /mnt/sd ] || mkdir -p /mnt/sd
    for dev in /dev/block/mmcblk1p1 /dev/block/mmcblk0p1; do
        [ -b "$dev" ] || continue
        if mount -t vfat "$dev" /mnt/sd 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

wait_sd() {
    n=0
    while [ $n -lt 30 ]; do
        mount_sd && return 0
        usleep 500000
        n=$((n + 1))
    done
    return 1
}

wait_sd
sleep 10
dump_state early

n=0
while [ $n -lt 300 ]; do
    boot_ok=$(getprop sys.init.boot_complete)
    debug_ok=$(getprop debug.malbec.bootok)
    if [ "$boot_ok" = "1" ] || [ "$debug_ok" = "1" ]; then
        exit 0
    fi
    sleep 1
    n=$((n + 1))
done

dump_state stall
sync
setprop sys.powerctl reboot,recovery
