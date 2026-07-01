@echo off
REM ---------------------------------------------------------------------------
REM One-command build + run for beginners (Windows / cmd.exe).
REM Double-click or run from anywhere: it switches to this script's own folder.
REM data\ and logs\ are created next to this script (wechat-ilink-bot\).
REM Requires JDK 8+ and Maven 3.6.3+ on PATH.
REM NOTE: keep this file ASCII-only. cmd.exe parses .bat in the OEM code page,
REM so non-ASCII text here gets garbled and can break control flow.
REM Console encoding: JVM/cmd both default to the system code page (GBK on
REM zh-CN Windows), so Chinese logs render correctly with no chcp needed.
REM ---------------------------------------------------------------------------
cd /d "%~dp0"

echo Stopping any previous bot instance ...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { $_.CommandLine -like '*wechat-ilink-bot-1.0.0-SNAPSHOT.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" 2>nul

call mvn -q clean package -DskipTests
if errorlevel 1 (
    echo.
    echo Build failed. If a previous run is still open in another window,
    echo close it and run this script again.
    exit /b 1
)
java -DLOG_DIR=logs -jar "target\wechat-ilink-bot-1.0.0-SNAPSHOT.jar"
