param([int]$Port=8080)
$ErrorActionPreference='Stop'
$jar=Join-Path $PSScriptRoot 'jiujiu-server.jar'
if(-not(Test-Path -LiteralPath $jar)){throw '请在发布包目录中运行（缺少 jiujiu-server.jar）'}
$runtimeDirectory=Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$dbPath=(Join-Path $runtimeDirectory 'jiujiu').Replace('\','/')
$mediaPath=(Join-Path $runtimeDirectory 'media').Replace('\','/')
Write-Output "啾啾将启动于 http://127.0.0.1:$Port；Ctrl+C 停止。账号/动态/图片保存在发布包 data 目录。"
& java -jar $jar '--spring.profiles.active=h2' '--server.address=127.0.0.1' "--server.port=$Port" "--spring.datasource.url=jdbc:h2:file:$dbPath;MODE=MySQL;DATABASE_TO_LOWER=TRUE" "--jiujiu.media.directory=$mediaPath"
