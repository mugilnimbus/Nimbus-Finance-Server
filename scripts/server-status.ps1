$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Push-Location $projectRoot
try {
    Write-Host "Nimbus server containers"
    docker compose --profile tailscale --profile inference ps

    Write-Host ""
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/health/ready" -TimeoutSec 5
        Write-Host "Local API: $($health.status) (version $($health.version))"
    } catch {
        Write-Host "Local API: unavailable"
    }

    $inferenceContainer = docker compose --profile inference ps --status running --services 2>$null |
        Where-Object { $_ -eq "nimbus-inference" }
    if ($inferenceContainer) {
        try {
            $inferenceHealth = docker compose exec -T finance-api wget -q -O - http://nimbus-inference:8080/health 2>$null |
                ConvertFrom-Json
            if ($inferenceHealth.status -eq "ok") { Write-Host "Private inference: model engine ready" }
            else { Write-Host "Private inference: container running; model still loading" }
        } catch {
            Write-Host "Private inference: container running; model unavailable or still loading"
        }
    } else {
        Write-Host "Private inference: not started (run .\scripts\setup-server.ps1 -StartInference)"
    }

    try {
        $serverAddress = & (Join-Path $PSScriptRoot "get-server-address.ps1")
        Write-Host "Phone server address: $serverAddress"
    } catch {
        Write-Host "Phone server address: not available"
        Write-Host "  $($_.Exception.Message)"
    }

    $backupDirectory = Join-Path $projectRoot "data\backups"
    $latestBackup = Get-ChildItem -LiteralPath $backupDirectory -Filter "nimbus-*.dump.gpg" -File -ErrorAction SilentlyContinue |
        Where-Object {
            $verifiedPath = "$($_.FullName).verified"
            $checksumPath = "$($_.FullName).sha256"
            if (-not (Test-Path -LiteralPath $verifiedPath) -or -not (Test-Path -LiteralPath $checksumPath)) { return $false }
            $expected = ((Get-Content -LiteralPath $checksumPath -Raw) -split '\s+')[0]
            $actual = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            $expected -and $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase)
        } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($latestBackup) {
        Write-Host "Latest verified encrypted backup: $($latestBackup.Name) ($($latestBackup.Length) bytes)"
        $age = [DateTimeOffset]::UtcNow - [DateTimeOffset]$latestBackup.LastWriteTimeUtc
        Write-Host "Backup age: $([math]::Round($age.TotalHours, 1)) hour(s)"
    } else {
        $legacy = Get-ChildItem -LiteralPath $backupDirectory -Filter "nimbus-*.dump.gz.enc" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Length -gt 1024 } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($legacy) { Write-Host "Latest legacy backup (restore drill required): $($legacy.Name) ($($legacy.Length) bytes)" }
        else { Write-Host "Latest verified encrypted backup: none" }
    }
    $lastError = Join-Path $backupDirectory ".last-error"
    if (Test-Path -LiteralPath $lastError) { Write-Host "Backup alert: last automatic attempt failed at $((Get-Content -LiteralPath $lastError -Raw).Trim())" }
} finally {
    Pop-Location
}
