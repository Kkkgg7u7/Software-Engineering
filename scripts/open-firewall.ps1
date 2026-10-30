# =====================================================================
# 放行 Windows 防火墙端口（让其他玩家能连进来）
#
# 【怎么运行】
#   方式一（推荐）：双击同目录的  open-firewall.bat   —— 会自动弹出管理员确认框
#   方式二：右键"以管理员身份运行 PowerShell"，然后执行
#             powershell -ExecutionPolicy Bypass -File .\scripts\open-firewall.ps1
#
# 【为什么必须管理员】
#   创建/删除防火墙规则需要管理员权限，普通用户会报"拒绝访问"。
#
# 【为什么规则要覆盖 Public】
#   本机当前连的是 UESTC-WiFi 2，网络位置类型是 Public（公用网络）。
#   Windows 在 Public 配置文件下入站默认全部拦截，
#   如果规则只作用于 Private，别人依然连不进来 —— 所以这里用 -Profile Any。
# =====================================================================

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------- 权限检查 ----
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host ''
    Write-Host '× 需要管理员权限。' -ForegroundColor Red
    Write-Host '  最简单的做法：回到项目根目录，双击  一键放行防火墙.bat （会自动提权）' -ForegroundColor Yellow
    Write-Host '  或者：右键"以管理员身份运行 PowerShell"，然后执行' -ForegroundColor Yellow
    Write-Host '        powershell -ExecutionPolicy Bypass -File scripts\open-firewall.ps1' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

Write-Host ''
Write-Host '======================================================================' -ForegroundColor DarkCyan
Write-Host '  放行狼人杀服务端口' -ForegroundColor Cyan
Write-Host '======================================================================' -ForegroundColor DarkCyan
Write-Host ''
Write-Host "当前用户: $($identity.Name)  [管理员]" -ForegroundColor Green
Write-Host ''

# ------------------------------------------------------------ 端口表 ----
# 8080 = 后端 API + WebSocket
# 5173 = 前端开发服务器（pnpm dev）
# 4173 = 前端预览服务器（pnpm preview，正式演示建议用这个）
$ports = @(
    @{ Port = 8080; Label = '后端 API / WebSocket' },
    @{ Port = 5173; Label = '前端开发服务器 (pnpm dev)' },
    @{ Port = 4173; Label = '前端预览服务器 (pnpm preview)' }
)

# ------------------------------------------------------------ 放行 ----
Write-Host '[执行] 创建入站放行规则 ...' -ForegroundColor Yellow
$created = 0
foreach ($item in $ports) {
    $port = $item.Port
    $name = "Werewolf $port"
    $existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host ("  · {0,-14} 规则已存在，跳过" -f "$name")
        continue
    }
    try {
        New-NetFirewallRule `
            -DisplayName $name `
            -Description "狼人杀课程项目：$($item.Label)" `
            -Direction Inbound `
            -Protocol TCP `
            -LocalPort $port `
            -Action Allow `
            -Profile Any `
            -Enabled True `
            -ErrorAction Stop | Out-Null
        Write-Host ("  √ {0,-14} TCP {1} 已放行  ({2})" -f $name, $port, $item.Label) -ForegroundColor Green
        $created++
    } catch {
        Write-Host ("  × {0,-14} 创建失败: {1}" -f $name, $_.Exception.Message) -ForegroundColor Red
    }
}

# ------------------------------------------------------------ 校验 ----
Write-Host ''
Write-Host '[校验] 当前规则状态:' -ForegroundColor Yellow
Get-NetFirewallRule -DisplayName 'Werewolf *' -ErrorAction SilentlyContinue | ForEach-Object {
    $pf = $_ | Get-NetFirewallPortFilter -ErrorAction SilentlyContinue
    $profiles = ($_.Profile -join ',')
    Write-Host ("  {0,-14} 方向={1,-8} 动作={2,-6} 启用={3,-5} 配置文件={4,-20} 端口={5}" -f `
        $_.DisplayName, $_.Direction, $_.Action, $_.Enabled, $profiles, $pf.LocalPort)
}

# --------------------------------------------------- 打印可分享地址 ----
Write-Host ''
Write-Host '======================================================================' -ForegroundColor DarkCyan
Write-Host '  完成，可以开始联机了' -ForegroundColor Green
Write-Host '======================================================================' -ForegroundColor DarkCyan
Write-Host ''

try {
    $health = Invoke-RestMethod 'http://127.0.0.1:8080/api/health' -TimeoutSec 5
    $addrs = $health.data.localAddresses
    if ($addrs) {
        Write-Host '  把下面这个地址发给队友（他们浏览器直接打开）:' -ForegroundColor Yellow
        foreach ($a in $addrs) {
            Write-Host "      http://${a}:5173" -ForegroundColor Cyan
        }
    }
} catch {
    Write-Host '  （后端当前未运行，启动后访问 http://127.0.0.1:8080/api/health 可查看应分享的地址）' -ForegroundColor DarkGray
}

Write-Host ''
Write-Host '  提示：' -ForegroundColor DarkGray
Write-Host '   · 同校同学连校园网即可加入，不要求同一个 WiFi' -ForegroundColor DarkGray
Write-Host '   · 校外朋友若打不开，说明校园网封了入站端口，改用内网穿透：' -ForegroundColor DarkGray
Write-Host '       cloudflared tunnel --url http://localhost:5173' -ForegroundColor DarkGray
Write-Host '   · 玩完想收回权限，运行同目录的 close-firewall.ps1' -ForegroundColor DarkGray
Write-Host ''
