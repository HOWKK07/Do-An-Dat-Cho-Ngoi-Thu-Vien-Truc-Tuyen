#!/usr/bin/env sh
cd "$(dirname "$0")"
[ -f dist/FixBug-Client.jar ] || ./build.sh
exec java -jar dist/FixBug-Client.jar
