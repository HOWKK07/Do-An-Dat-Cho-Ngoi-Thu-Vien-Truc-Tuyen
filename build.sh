#!/usr/bin/env sh
# Biên dịch và đóng gói (Linux / macOS). Yêu cầu JDK 11+ và Internet ở lần đầu (tải driver MySQL).
set -e
cd "$(dirname "$0")"
DRIVER=mysql-connector-j-8.4.0.jar
DRIVER_URL=https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/$DRIVER

mkdir -p lib
if [ ! -f "lib/$DRIVER" ]; then
    echo "Đang tải MySQL Connector/J 8.4.0..."
    curl -fL -o "lib/$DRIVER" "$DRIVER_URL" || { echo "Không tải được, hãy tải thủ công $DRIVER_URL vào thư mục lib/"; exit 1; }
fi

rm -rf build && mkdir -p build/classes dist/lib
echo "Đang biên dịch..."
javac --release 11 -encoding UTF-8 -d build/classes -sourcepath src/main/java \
    src/main/java/library/server/ServerMain.java \
    src/main/java/library/client/ClientMain.java \
    src/main/java/library/tools/StressTest.java
printf 'Main-Class: library.server.ServerMain\nClass-Path: lib/%s\n' "$DRIVER" > build/manifest-server.txt
cp "lib/$DRIVER" dist/lib/
jar cfm dist/FixBug-Server.jar build/manifest-server.txt -C build/classes .
jar cfe dist/FixBug-Client.jar library.client.ClientMain -C build/classes .
echo "Xong: dist/FixBug-Server.jar (+ dist/lib/$DRIVER), dist/FixBug-Client.jar"
