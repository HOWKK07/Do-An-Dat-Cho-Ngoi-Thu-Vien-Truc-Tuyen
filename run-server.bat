@echo off
cd /d "%~dp0"
chcp 65001 >nul
if not exist dist\lib\mysql-connector-j-8.4.0.jar call build.bat
if not exist dist\FixBug-Server.jar call build.bat
rem Server doc cau hinh MySQL tu file db.properties trong thu muc nay.
rem Them tham so neu can, vi du:  run-server.bat --port 6000 --session-timeout 10
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar dist\FixBug-Server.jar %*
pause
