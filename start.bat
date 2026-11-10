@echo off
setlocal
title CarRent - Local Launcher
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" %*
set "CARRENT_EXIT=%ERRORLEVEL%"
echo.
if not "%CARRENT_EXIT%"=="0" echo CarRent needs attention. Read the SETUP-NEEDED.md path printed above.
if not defined CARRENT_LAUNCHER_NO_PAUSE pause
exit /b %CARRENT_EXIT%
