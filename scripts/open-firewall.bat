@echo off
REM =====================================================================
REM  双击本文件即可放行防火墙端口（会弹出"是否允许"的管理员确认框）
REM
REM  原理：Windows 不允许普通权限进程创建防火墙规则，
REM        所以这里用 PowerShell 的 RunAs 触发 UAC 提权，
REM        再以管理员身份执行 scripts\open-firewall.ps1。
REM =====================================================================
setlocal
cd /d "%~dp0"

echo.
echo   正在请求管理员权限 ...
echo   （会弹出一个 UAC 确认框，请点击"是"）
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','\"%~dp0open-firewall.ps1\"'"

if errorlevel 1 (
  echo.
  echo   [失败] 提权被取消或失败。
  echo   你也可以手动操作：右键"以管理员身份运行 PowerShell"，然后执行
  echo       powershell -ExecutionPolicy Bypass -File "%~dp0open-firewall.ps1"
  echo.
  pause
)

endlocal
