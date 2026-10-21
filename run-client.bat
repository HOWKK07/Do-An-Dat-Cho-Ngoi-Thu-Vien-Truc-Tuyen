@echo off
cd /d "%~dp0"
if not exist dist\FixBug-Client.jar call build.bat
start "" javaw -jar dist\FixBug-Client.jar
