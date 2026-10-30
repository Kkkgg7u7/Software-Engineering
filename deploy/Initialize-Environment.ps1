param([Parameter(Mandatory=$true)][string]$Origin)
$ErrorActionPreference='Stop'
$uri=[Uri]$Origin
if($uri.Scheme -ne 'https' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo -or $Origin.EndsWith('/')){throw 'Origin 必须为不带路径和尾斜杠的精确 HTTPS 来源，例如 https://jiujiu.example.com'}
$target=Join-Path $PSScriptRoot '.env'
if(Test-Path -LiteralPath $target){throw 'deploy/.env 已存在，请手工检查，脚本不会覆盖配置'}
function New-Secret([int]$Size){$buffer=New-Object byte[] $Size;[System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer);return [Convert]::ToBase64String($buffer)}
$content=Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.example') -Raw
$content=$content.Replace('MYSQL_ROOT_PASSWORD=',('MYSQL_ROOT_PASSWORD='+(New-Secret 32))).Replace('MYSQL_PASSWORD=',('MYSQL_PASSWORD='+(New-Secret 32))).Replace('WEREWOLF_JWT_SECRET=',('WEREWOLF_JWT_SECRET='+(New-Secret 48))).Replace('WEREWOLF_CORS_ORIGINS=https://jiujiu.example.com',('WEREWOLF_CORS_ORIGINS='+$Origin))
Set-Content -LiteralPath $target -Value $content -Encoding utf8
Write-Output '已创建 deploy/.env。密钥未输出，请保持文件私密；配置真实证书和 ICE 服务后再启动。'
