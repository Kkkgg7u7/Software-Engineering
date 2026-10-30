# =====================================================================
# 收回防火墙放行（玩完之后恢复原状）
#
# 作用：删除 open-firewall.ps1 创建的 3 条入站规则，把机器恢复到
#       "外部无法访问本项目端口"的状态，避免长期把服务暴露在校园网上。
#
# 用法：右键"以管理员身份运行 PowerShell"，然后执行
#         powershell -ExecutionPolicy Bypass -File .\scripts\close-firewall.ps1
# =====================================================================

$ErrorActionPreference = 'Continue'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host ''
    Write-Host '× 需要管理员权限，请右键"以管理员身份运行 PowerShell"后重试。' -ForegroundColor Red
    Write-Host ''
    exit 1
}

Write-Host ''
Write-Host '正在删除狼人杀相关的防火墙规则 ...' -ForegroundColor Yellow

$rules = Get-NetFirewallRule -DisplayName 'Werewolf *' -ErrorAction SilentlyContinue
if (-not $rules) {
    Write-Host '  · 没有找到相关规则，无需清理。' -ForegroundColor DarkGray
    exit 0
}

$removed = 0
foreach ($rule in $rules) {
    try {
        Remove-NetFirewallRule -DisplayName $rule.DisplayName -ErrorAction Stop
        Write-Host ("  √ 已删除: {0}" -f $rule.DisplayName) -ForegroundColor Green
        $removed++
    } catch {
        Write-Host ("  × 删除失败: {0} -> {1}" -f $rule.DisplayName, $_.Exception.Message) -ForegroundColor Red
    }
}

Write-Host ''
Write-Host "完成，共删除 $removed 条规则。现在外部已无法访问本机的 8080 / 5173 / 4173。" -ForegroundColor Cyan
Write-Host '（本机自己访问 localhost 不受影响，规则只拦截来自外部的连接。）' -ForegroundColor DarkGray
Write-Host ''
