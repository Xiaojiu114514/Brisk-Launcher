@echo off
rem Console entry (keeps the console window open with logs).
rem Java detection/install is done by launch.ps1.
rem   --check    print the Java that would be used
rem   --install  only download + install Java 21
rem   --safe     software rendering (old graphics drivers)
set "PS=powershell -NoProfile -ExecutionPolicy Bypass"
if /i "%~1"=="--check" (
    %PS% -File "%~dp0launch.ps1" -Mode check
    exit /b
)
if /i "%~1"=="--install" (
    %PS% -File "%~dp0launch.ps1" -Mode install
    exit /b
)
if /i "%~1"=="--safe" (
    %PS% -File "%~dp0launch.ps1" -Mode "console safe"
    exit /b
)
%PS% -File "%~dp0launch.ps1" -Mode console
if errorlevel 1 (
    echo.
    echo Brisk Launcher failed to start. See the messages above.
    pause
)
