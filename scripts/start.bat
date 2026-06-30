@echo off
chcp 65001 >nul
title APKSlicer
cd /d "%~dp0app"
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8
echo ==============================================
echo          APKSlicer Starting...
echo ==============================================
echo Application will be available at:
echo http://localhost/
echo ==============================================
echo NOTE: Keep this window open while using the application
echo ==============================================
start http://localhost/
"%~dp0APKSlicer.exe"
pause
