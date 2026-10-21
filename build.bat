@echo off
setlocal
cd /d "%~dp0"
set DRIVER=mysql-connector-j-8.4.0.jar
set DRIVER_URL=https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar

where javac >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay javac. Hay cai JDK 11 tro len va them thu muc bin vao PATH.
    pause
    exit /b 1
)

rem ---- Tai driver MySQL Connector/J (chi lan dau, can Internet) ----
if not exist lib mkdir lib
if not exist lib\%DRIVER% (
    echo Dang tai thu vien MySQL Connector/J 8.4.0 tu Maven Central...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%DRIVER_URL%' -OutFile 'lib\%DRIVER%'"
)
if not exist lib\%DRIVER% (
    echo [LOI] Khong tai duoc %DRIVER%.
    echo Hay tai thu cong tu %DRIVER_URL%
    echo roi dat vao thu muc lib\ cua du an, sau do chay lai build.bat.
    pause
    exit /b 1
)

rem ---- Bien dich ----
if exist build rmdir /s /q build
mkdir build\classes
echo Dang bien dich ma nguon...
javac --release 11 -encoding UTF-8 -d build\classes -sourcepath src\main\java ^
    src\main\java\library\server\ServerMain.java ^
    src\main\java\library\client\ClientMain.java ^
    src\main\java\library\tools\StressTest.java
if errorlevel 1 (
    echo [LOI] Bien dich that bai.
    pause
    exit /b 1
)

rem ---- Dong goi jar ----
> build\manifest-server.txt echo Main-Class: library.server.ServerMain
>> build\manifest-server.txt echo Class-Path: lib/%DRIVER%
if not exist dist\lib mkdir dist\lib
copy /y lib\%DRIVER% dist\lib\ >nul
jar cfm dist\FixBug-Server.jar build\manifest-server.txt -C build\classes .
jar cfe dist\FixBug-Client.jar library.client.ClientMain -C build\classes .
echo.
echo Xong! Da tao:
echo    dist\FixBug-Server.jar  + dist\lib\%DRIVER%   (may chu, can MySQL)
echo    dist\FixBug-Client.jar   (ung dung cho sinh vien / quan tri - copy sang may khac trong LAN)
echo.
pause
