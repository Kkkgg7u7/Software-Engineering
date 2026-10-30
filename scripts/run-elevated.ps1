# =====================================================================
# 提权执行包装脚本（由 一键放行防火墙.bat 调用，一般不需要手动运行）
#
# 为什么要这个包装脚本：
#   UAC 提权后新开的窗口执行完就会关闭，用户根本看不到"成功/失败"和该分享的地址。
#   所以这里把执行结果写入一个日志文件，并停在原地让用户看结果，
#   最后再提示日志路径，方便出问题时排查。
# =====================================================================

$logFile = Join-Path $PSScriptRoot 'firewall-result.log'
$target = Join-Path $PSScriptRoot 'open-firewall.ps1'

Start-Transcript -Path $logFile -Force | Out-Null
$exitCode = 0
try {
    if (-not (Test-Path $target)) {
        Write-Host "× 未找到 $target" -ForegroundColor Red
        $exitCode = 1
    } else {
        & $target
        $exitCode = $LASTEXITCODE
    }
} catch {
    Write-Host "× 执行出错: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
} finally {
    Write-Host ''
    Write-Host "执行日志已保存到: $logFile" -ForegroundColor DarkGray
    Stop-Transcript | Out-Null
}

Write-Host ''
Write-Host '按任意键关闭本窗口 ...' -ForegroundColor DarkGray
$null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
exit $exitCode
