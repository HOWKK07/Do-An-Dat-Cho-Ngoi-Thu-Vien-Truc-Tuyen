#!/usr/bin/env sh
cd "$(dirname "$0")"
[ -f dist/lib/mysql-connector-j-8.4.0.jar ] && [ -f dist/FixBug-Server.jar ] || ./build.sh
exec java -Dfile.encoding=UTF-8 -jar dist/FixBug-Server.jar "$@"
