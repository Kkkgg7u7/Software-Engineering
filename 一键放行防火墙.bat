@echo off
REM =====================================================================
REM  Allow inbound firewall ports for the Werewolf game (one-click).
REM  Double-click this file.
REM
REM  It will:
REM    1. Show a UAC prompt  ->  click "Yes"
REM    2. Allow inbound TCP 8080 / 5173 / 4173
REM    3. Keep the result window open; log goes to scripts\firewall-result.log
REM
REM  NOTE: this file is intentionally ASCII-only, because cmd.exe decodes
REM  .bat files with the system ANSI code page and would garble Chinese text.
REM
REM  Manual alternative (if double-click does not work):
REM    Right-click PowerShell -> "Run as administrator", then run:
REM      powershell -ExecutionPolicy Bypass -File scripts\open-firewall.ps1
REM =====================================================================
setlocal
cd /d "%~dp0"

echo.
echo   ============================================================
echo    Open firewall ports for the Werewolf game (needs admin)
echo   ============================================================
echo.
echo   A UAC prompt will appear. Click "Yes" to continue ...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"%~dp0scripts\run-elevated.ps1\"'"

if errorlevel 1 (
  echo.
  echo   [FAILED] Elevation was cancelled or failed.
  echo.
  echo   Please do it manually:
  echo     Right-click PowerShell -^> "Run as administrator", then run:
  echo       powershell -ExecutionPolicy Bypass -File "%~dp0scripts\open-firewall.ps1"
  echo.
  pause
)

endlocal
