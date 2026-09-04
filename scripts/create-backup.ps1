$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $projectRoot
try {
    docker compose run --rm --no-deps finance-backup --once
    if ($LASTEXITCODE -ne 0) { throw "Verified encrypted backup failed" }
} finally {
    Pop-Location
}
