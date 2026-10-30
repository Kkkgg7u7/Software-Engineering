# =====================================================================
# 局域网联机一键启动（Windows PowerShell）
#
# 用法（在 werewolf-game 目录下执行）：
#     powershell -ExecutionPolicy Bypass -File .\start-lan.ps1
#
# 它会做三件事：
#   1. 用【文件库】模式启动后端（注册的账号重启后不丢）
#   2. 用 --host 启动前端，让别人也能访问
#   3. 打印出"该发给别人"的地址
#
# 前置条件：
#   * 已执行过  cd backend; mvn -DskipTests package   得到 target/werewolf-server.jar
#   * frontend 已执行过  pnpm install
#   * 已放行防火墙（脚本会检测并提示，不会自动改防火墙）
# =====================================================================

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$backendDir = Join-Path $root 'backend'
$frontendDir = Join-Path $root 'frontend'
$jar = Join-Path $backendDir 'target\werewolf-server.jar'

Write-Host ''
Write-Host '======================================================================' -ForegroundColor DarkCyan
Write-Host '  AI 狼人杀 · 局域网联机启动' -ForegroundColor Cyan
Write-Host '======================================================================' -ForegroundColor DarkCyan

# ---------------------------------------------------------- 前置检查 ----
if (-not (Test-Path $jar)) {
    Write-Host ''
    Write-Host '× 未找到后端 jar，请先打包：' -ForegroundColor Red
    Write-Host '    cd backend'
    Write-Host '    mvn -DskipTests package'
    exit 1
}

if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
    Write-Host ''
    Write-Host '× 前端依赖未安装，请先执行：' -ForegroundColor Red
    Write-Host '    cd frontend'
    Write-Host '    pnpm install'
    exit 1
}

# 取本机局域网 IP
try {
    $ip = (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        Sort-Object -Property SkipAsSource |
        Select-Object -First 1).IPAddress
} catch {
    $ip = '127.0.0.1'
}
if (-not $ip) { $ip = '127.0.0.1' }

# ------------------------------------------------------ 启动后端 ----
Write-Host ''
Write-Host '[1/2] 启动后端（文件库模式，端口 8080）...' -ForegroundColor Yellow

# 文件库：注册的账号在重启后仍然存在（不设这个就是内存库，重启清空）
$env:WEREWOLF_DB_URL = 'jdbc:h2:file:./data/werewolf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE'

$backend = Start-Process -FilePath 'java' `
    -ArgumentList '-Dfile.encoding=UTF-8', '-jar', 'target\werewolf-server.jar', '--spring.profiles.active=h2' `
    -WorkingDirectory $backendDir -PassThru -WindowStyle Minimized

Write-Host '      等待后端就绪 ...' -NoNewline
$ready = $false
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 1
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect('127.0.0.1', 8080)
        $tcp.Close()
        $ready = $true
        break
    } catch { }
}
if (-not $ready) {
    Write-Host ' 超时' -ForegroundColor Red
    Write-Host '× 后端启动失败，请检查 8080 端口是否被占用' -ForegroundColor Red
    if ($backend -and -not $backend.HasExited) { Stop-Process -Id $backend.Id -Force }
    exit 1
}
Write-Host ' √ 就绪' -ForegroundColor Green

# ------------------------------------------------------ 启动前端 ----
Write-Host '[2/2] 启动前端（监听 0.0.0.0:5173）...' -ForegroundColor Yellow
$frontend = Start-Process -FilePath 'pnpm' `
    -ArgumentList 'dev', '--host', '--port', '5173' `
    -WorkingDirectory $frontendDir -PassThru -WindowStyle Minimized
Start-Sleep -Seconds 6

# ---------------------------------------------------------- 收尾 ----
Write-Host ''
Write-Host '======================================================================' -ForegroundColor DarkCyan
Write-Host ' 启动完成！' -ForegroundColor Green
Write-Host '======================================================================' -ForegroundColor DarkCyan
Write-Host ''
Write-Host "  你自己打开   : http://localhost:5173"
Write-Host "  发给别人     : http://${ip}:5173" -ForegroundColor Cyan
Write-Host ''
Write-Host '  演示账号     : demo / player1 / player2 / player3（密码都是 demo1234）'
Write-Host '  同一台电脑开多个窗口测试时，请用无痕窗口，否则会共用登录态。'
Write-Host ''
Write-Host '  别人打不开？多数是防火墙没放行。管理员 PowerShell 执行一次即可：' -ForegroundColor Yellow
Write-Host '    New-NetFirewallRule -DisplayName "Werewolf 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow'
Write-Host '    New-NetFirewallRule -DisplayName "Werewolf 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Action Allow'
Write-Host ''
Write-Host '  想让不在同一网络的同学也能玩，见 docs/NETWORK_DEPLOY.md 的方案 B（内网穿透）。'
Write-Host ''
Write-Host "  进程号：后端 $($backend.Id) / 前端 $($frontend.Id)"
Write-Host "  停止服务：Stop-Process -Id $($backend.Id),$($frontend.Id)" -ForegroundColor DarkGray
Write-Host ''
