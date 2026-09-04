$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adminKeyPath = Join-Path $projectRoot ".secrets\finance_admin_key"
if (-not (Test-Path -LiteralPath $adminKeyPath)) { throw "Run .\scripts\setup-server.ps1 first" }

$adminKey = [IO.File]::ReadAllText($adminKeyPath).Trim()
if ([string]::IsNullOrWhiteSpace($adminKey)) { throw "The server administrator key is missing" }

try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/health/ready" -TimeoutSec 5
    if ($health.status -ne "ready") { throw "The local API is not ready" }
    $launch = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/v1/dashboard/admin/launch" `
        -Headers @{ "X-Admin-Key" = $adminKey } -ContentType "application/json" -Body "{}" -TimeoutSec 10
    $dashboardUrl = "http://127.0.0.1:8080/dashboard#owner=$([Uri]::EscapeDataString($launch.token))"
    Start-Process $dashboardUrl
    Write-Host "Nimbus owner dashboard opened in your browser."
    Write-Host "The one-use login link expires in 60 seconds and was not printed."
} finally {
    $adminKey = $null
    $launch = $null
    $dashboardUrl = $null
}
