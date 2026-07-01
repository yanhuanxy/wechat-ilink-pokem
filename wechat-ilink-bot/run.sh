#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# One-command build + run for beginners (Linux / macOS / Git Bash).
# Run from anywhere: it switches to this script's own folder.
# data/ and logs/ are created next to this script (wechat-ilink-bot/).
# Requires JDK 8+ and Maven 3.6.3+ on PATH.
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

echo "停止可能仍在运行的旧实例 ..."
if [ "${OS:-}" = "Windows_NT" ]; then
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -like '*wechat-ilink-bot-1.0.0-SNAPSHOT.jar*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1 || true
else
    pkill -f "wechat-ilink-bot-1.0.0-SNAPSHOT.jar" 2>/dev/null || true
fi

if ! mvn -q clean package -DskipTests; then
    echo "构建失败：若仍有旧实例在运行会锁定 target/ 里的 jar，请关闭后重试。" >&2
    exit 1
fi
java -Dfile.encoding=UTF-8 -DLOG_DIR=logs -jar "target/wechat-ilink-bot-1.0.0-SNAPSHOT.jar"
