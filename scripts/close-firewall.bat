@echo off
REM =====================================================================
REM  Close (remove) the firewall rules created by open-firewall.ps1.
REM  Double-click this file after you finish playing.
REM
REM  ASCII-only on purpose: cmd.exe would garble Chinese text.
REM =====================================================================
setlocal
cd /d "%~dp0"

echo.
echo   Removing Werewolf firewall rules (needs admin) ...
echo   A UAC prompt will appear. Click "Yes" to continue ...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','\"%~dp0scripts\close-firewall.ps1\"'"

if errorlevel 1 (
  echo.
  echo   [FAILED] Elevation was cancelled or failed.
  echo   Manual: right-click PowerShell -^> "Run as administrator", then run:
  echo     powershell -ExecutionPolicy Bypass -File "%~dp0scripts\close-firewall.ps1"
  echo.
  pause
)

endlocal
