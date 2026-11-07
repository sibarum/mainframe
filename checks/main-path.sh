#!/usr/bin/env bash
# The main path, driven by ottermate on a home of the run's own (docs/shipping.md). The exit status is the verdict:
# 0 every step answered ok, 1 a step did not (its reply says which), 3 or 4 the application did not start or went away.
#
#   checks/main-path.sh                         the native debug build (native-build.cmd -Pnative, or -Pnative-linux)
#   checks/main-path.sh mvn exec:exec -pl mainframe-gui   on the JVM, from the repository's folder
#
# OTTERMATE names the client; it defaults to the sibling vexelray-gui checkout's.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) target="$root/mainframe-gui/target"; exe="$target/mainframe-debug.exe"; ott="ottermate.cmd"
                          native() { cygpath -w "$1"; } ;;
    *)                    target="$root/mainframe-gui/target-linux"; exe="$target/mainframe"; ott="ottermate"
                          native() { printf '%s' "$1"; } ;;
esac
ottermate="${OTTERMATE:-$root/../vexelray-gui/vexelray-gui-automation-cli/$ott}"
home="$target/check-home"
shots="$target/check"

# A home with one workspace, day, whose two folders are in it.
rm -rf "$home" "$shots"
mkdir -p "$home/work" "$home/web" "$home/.mainframe/workspaces" "$shots"
printf 'name,path,env\r\n,%s,work-c0ffee.ps1\r\nfront,%s,front-c0ffee.ps1\r\n' \
    "$(native "$home/work")" "$(native "$home/web")" > "$home/.mainframe/workspaces/day.csv"

if [ $# -eq 0 ]; then
    set -- "$exe" "-Duser.home=$(native "$home")" -Dmainframe.launch=off --automation=0
elif [ "$1" = mvn ] || [ "$1" = mvn.cmd ]; then
    cmd="$1"; shift
    [ "$cmd" = mvn ] && [ "$ott" = ottermate.cmd ] && cmd=mvn.cmd   # ProcessBuilder does not read PATHEXT
    set -- "$cmd" "$@" -Dautomation=0 "-Dapp.jvmArgs=-Duser.home=$(native "$home") -Dmainframe.launch=off"
fi

cd "$root"
shots_path="$(native "$shots")"
shots_path="${shots_path//\\//}"   # forward slashes, which sed and the script both take as they are
sed "s|@SHOTS@|$shots_path|g" "$here/main-path.txt" > "$target/main-path.script"
status=0
"$ottermate" --script "$target/main-path.script" --launch "$@" || status=$?
if [ $status -ne 0 ]; then
    echo "main-path: ottermate stopped with status $status; the shots so far are in $shots" >&2
    exit $status
fi

# What the Workspaces window did, on disk: the workspace made, named and redone is there, and day is as it was.
ws="$home/.mainframe/workspaces"
if [ ! -f "$ws/evening.csv" ] || [ -f "$ws/New workspace.csv" ] || [ ! -f "$ws/day.csv" ]; then
    echo "main-path: the workspaces folder is not as the steps left it:" >&2
    ls -A "$ws" >&2
    exit 1
fi
echo "main-path: ok; the shots are in $shots"
