@echo off
cd /d "%~dp0"
chcp 65001 >nul
rem Kiem thu: 20 client cung luc dat CUNG MOT cho. Ket qua dung: chi 1 nguoi thanh cong.
rem Can chay server truoc (run-server.bat).
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp dist\FixBug-Server.jar library.tools.StressTest localhost 5050 20
pause
