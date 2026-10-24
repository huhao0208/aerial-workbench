#!/system/bin/sh
# One explicit, verified self-update on the owner TCL. No permanent policy change.
set -eu
umask 077
stage=/data/adb/aerial-updater
bb=/data/adb/magisk/busybox
uri=content://com.tcl.providers.config/InstallConfig
where="project_id='InstallConfig'"
# Binder shell commands cannot receive an adb_data_file stdout/stderr FD on this
# Enforcing TCL. Capture both streams through a pipe, then write the private log.
android_cmd() {
    if output=$("$@" 2>&1); then printf '%s\n' "$output";
    else code=$?; printf '%s\n' "$output"; return "$code"; fi
}
normal='[{"enable":"true","strategies":[{"name":"safeStrategy","enable":"true","priority":"0"},{"name":"blackListStrategy","enable":"true","packages":[],"priority":"1"},{"name":"thridStrategy","enable":"false","packages":["com.dangbeimarket","com.shafa.market","com.ant.store.appstore"],"priority":"2"},{"name":"launcherStrategy","enable":"true","packages":[],"priority":"3"},{"name":"overDueStrategy","enable":"true","packages":[],"priority":"4"},{"name":"pmStrategy","enable":"true","priority":"5"}]}]'
stock=$normal
home='[{"enable":"true","strategies":[{"name":"safeStrategy","enable":"true","priority":"0"},{"name":"blackListStrategy","enable":"true","packages":[],"priority":"1"},{"name":"thridStrategy","enable":"false","packages":["com.dangbeimarket","com.shafa.market","com.ant.store.appstore"],"priority":"2"},{"name":"launcherStrategy","enable":"false","packages":[],"priority":"3"},{"name":"overDueStrategy","enable":"true","packages":[],"priority":"4"},{"name":"pmStrategy","enable":"true","priority":"5"}]}]'
home_adb='[{"enable":"true","strategies":[{"name":"safeStrategy","enable":"true","priority":"0"},{"name":"blackListStrategy","enable":"true","packages":[],"priority":"1"},{"name":"thridStrategy","enable":"false","packages":["com.dangbeimarket","com.shafa.market","com.ant.store.appstore"],"priority":"2"},{"name":"launcherStrategy","enable":"false","packages":[],"priority":"3"},{"name":"overDueStrategy","enable":"true","packages":[],"priority":"4"},{"name":"pmStrategy","enable":"false","priority":"5"}]}]'
temporary='[{"enable":"true","strategies":[{"name":"safeStrategy","enable":"true","priority":"0"},{"name":"blackListStrategy","enable":"true","packages":[],"priority":"1"},{"name":"thridStrategy","enable":"false","packages":["com.dangbeimarket","com.shafa.market","com.ant.store.appstore"],"priority":"2"},{"name":"launcherStrategy","enable":"true","packages":["app.hzf.aerial"],"priority":"3"},{"name":"overDueStrategy","enable":"true","packages":[],"priority":"4"},{"name":"pmStrategy","enable":"false","priority":"5"}]}]'
query() { "$bb" timeout 10 content query --uri "$uri" --projection config_content --where "$where"; }
write_config() { "$bb" timeout 10 content update --uri "$uri" --bind "config_content:s:$(printf '%s' "$1" | sed 's/:/\\:/g')" --where "$where"; }
known_baseline() {
    case "$1" in "$stock"|"$home"|"$home_adb") return 0;; *) return 1;; esac
}
select_baseline() {
    row=$(query) || return 1
    case "$row" in 'Row: 0 config_content='*) normal=${row#'Row: 0 config_content='};; *) return 1;; esac
    known_baseline "$normal"
}
restore() {
    if [ -f "$stage/baseline" ]; then normal=$(cat "$stage/baseline"); fi
    known_baseline "$normal" || return 1
    current=$(query) || return 1
    if [ "$current" = "Row: 0 config_content=$temporary" ]; then
        write_config "$normal" || return 1
        android_cmd am force-stop com.android.packageinstaller
    fi
    [ "$(query)" = "Row: 0 config_content=$normal" ] || return 1
    rm -f "$stage/pending"
}
[ "$(id -u)" = 0 ] && [ "$(getprop ro.product.device)" = tc8000_ay30a2 ] && [ -x "$bb" ] || exit 20
case "${1:-}" in
    --recover)
        [ -e "$stage/pending" ] || { rmdir "$stage/lock" 2>/dev/null || true; exit 0; }
        # Magisk service.d runs before providers are always ready.
        attempt=0
        until query >/dev/null 2>&1; do
            attempt=$((attempt+1)); [ "$attempt" -lt 90 ] || exit 21; sleep 2
        done
        restore
        rmdir "$stage/lock" 2>/dev/null || true
        exit 0;;
    --check)
        select_baseline
        echo 'WB-UPDATE CHECK_OK'; exit 0;;
    --worker) ;;
    *) exit 22;;
esac
[ ! -e "$stage/pending" ] || restore
select_baseline || exit 23
# Content is copied into this root-owned directory before the policy window opens.
[ -f "$stage/update.apk" ] && [ ! -L "$stage/update.apk" ] || exit 24
printf '%s  %s\n' "$2" "$stage/update.apk" | sha256sum -c - || exit 25
# A reboot restores the exact known baseline before any future update.
mkdir -p /data/adb/service.d
printf '#!/system/bin/sh\n/system/bin/sh /data/adb/aerial-updater/run.sh --recover >> /data/adb/aerial-updater/recovery.log 2>&1\n' > /data/adb/service.d/20-aerial-update-restore.sh
chmod 0755 /data/adb/service.d/20-aerial-update-restore.sh
printf '%s\n' "$normal" > "$stage/baseline.next"
mv "$stage/baseline.next" "$stage/baseline"
touch "$stage/pending"
finish() {
    code=$?
    trap - EXIT
    if restore; then
        printf '%s\n' "$code" > "$stage/result"
    else
        printf '30\n' > "$stage/result"
        exit 30
    fi
    if [ "$code" = 0 ]; then
        android_cmd "$bb" timeout 60 cmd package compile -m speed -f app.hzf.aerial || true
        android_cmd am start --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
    fi
    rm -f "$stage/update.apk"
    rmdir "$stage/lock" || true
    exit "$code"
}
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
write_config "$temporary"
[ "$(query)" = "Row: 0 config_content=$temporary" ] || exit 26
android_cmd am force-stop com.android.packageinstaller
# Stream from a pipe: system_server cannot open adb_data_file APK paths either.
cat "$stage/update.apk" | android_cmd "$bb" timeout 90 pm install --user 0 -r -S "$(stat -c %s "$stage/update.apk")" -- - > "$stage/install.log"
grep -qx 'Success' "$stage/install.log" || exit 27
