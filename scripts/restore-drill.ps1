$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backupDirectory = (Resolve-Path (Join-Path $projectRoot "data\backups")).Path
$verifiedCurrent = Get-ChildItem -LiteralPath $backupDirectory -Filter "nimbus-*.dump.gpg" -File |
    Where-Object { Test-Path -LiteralPath "$($_.FullName).verified" } |
    Sort-Object LastWriteTimeUtc -Descending
$legacy = Get-ChildItem -LiteralPath $backupDirectory -Filter "nimbus-*.dump.gz.enc" -File |
    Where-Object { $_.Length -gt 1024 } |
    Sort-Object LastWriteTimeUtc -Descending
$latest = @($verifiedCurrent) + @($legacy) | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not $latest) { throw "No encrypted backup exists under $backupDirectory" }

$containerName = "nimbus-restore-drill-$([guid]::NewGuid().ToString('N').Substring(0, 8))"

Push-Location $projectRoot
try {
    $databaseContainer = docker compose ps -q finance-db
    if (-not $databaseContainer) { throw "The Finance database container is not running" }
    $network = docker inspect $databaseContainer --format '{{range $name, $details := .NetworkSettings.Networks}}{{$name}}{{end}}'
    if (-not $network) { throw "Could not resolve the isolated Finance database network" }
    $temporaryPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    docker run --detach --rm --name $containerName --network $network `
        --env "POSTGRES_PASSWORD=$temporaryPassword" postgres:17-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not start isolated restore database" }

    $ready = $false
    1..30 | ForEach-Object {
        if (-not $ready) {
            docker exec $containerName pg_isready -U postgres 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true } else { Start-Sleep -Milliseconds 500 }
        }
    }
    if (-not $ready) { throw "Restore database did not become ready" }

    $decrypt = if ($latest.Name.EndsWith('.gpg')) {
        "export GNUPGHOME=/tmp/gnupg; mkdir -p -m 0700 `$GNUPGHOME; gpg --batch --yes --pinentry-mode loopback --passphrase-file /run/secrets/backup_passphrase --decrypt /backups/$($latest.Name)"
    } else {
        "openssl enc -d -aes-256-cbc -pbkdf2 -pass file:/run/secrets/backup_passphrase -in /backups/$($latest.Name) | gzip -d"
    }
    $restoreCommand = "export PGPASSWORD='$temporaryPassword'; createdb -h $containerName -U postgres nimbus_restore && " +
        "$decrypt | pg_restore -h $containerName -U postgres -d nimbus_restore --no-owner --exit-on-error"
    docker compose run --rm --no-deps --entrypoint sh finance-backup -c $restoreCommand
    if ($LASTEXITCODE -ne 0) { throw "Backup restore failed" }

    $counts = docker exec $containerName psql -U postgres -d nimbus_restore -Atc `
        "SELECT 'users='||count(*) FROM users UNION ALL SELECT 'groups='||count(*) FROM finance_groups UNION ALL SELECT 'changes='||count(*) FROM change_log;"
    if ($LASTEXITCODE -ne 0) { throw "Restored database verification query failed" }
    Write-Host "Restore drill passed using $($latest.Name)"
    $counts | ForEach-Object { Write-Host $_ }
} finally {
    docker container inspect $containerName *> $null
    if ($LASTEXITCODE -eq 0) { docker rm -f $containerName | Out-Null }
    Pop-Location
}
