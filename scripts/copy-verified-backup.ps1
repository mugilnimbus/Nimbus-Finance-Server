param(
    [string]$Destination = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backupDirectory = Join-Path $projectRoot "data\backups"
if (-not $Destination) {
    $oneDrive = [Environment]::GetEnvironmentVariable("OneDrive")
    if (-not $oneDrive) { throw "Pass -Destination with a protected directory on another device or encrypted cloud drive" }
    $Destination = Join-Path $oneDrive "Nimbus verified backups"
}
$resolvedDestination = [IO.Path]::GetFullPath($Destination)
if ($resolvedDestination.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The off-host copy must be outside the Nimbus repository"
}
$latest = Get-ChildItem -LiteralPath $backupDirectory -Filter "nimbus-*.dump.gpg" -File |
    Where-Object { (Test-Path -LiteralPath "$($_.FullName).verified") -and (Test-Path -LiteralPath "$($_.FullName).sha256") } |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not $latest) { throw "No verified authenticated backup is available to copy" }
$expected = ((Get-Content -LiteralPath "$($latest.FullName).sha256" -Raw) -split '\s+')[0]
$actual = (Get-FileHash -LiteralPath $latest.FullName -Algorithm SHA256).Hash
if (-not $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) { throw "The latest backup checksum does not match" }
New-Item -ItemType Directory -Force -Path $resolvedDestination | Out-Null
foreach ($source in @($latest.FullName, "$($latest.FullName).sha256", "$($latest.FullName).verified")) {
    Copy-Item -LiteralPath $source -Destination $resolvedDestination -Force
}
$copied = Join-Path $resolvedDestination $latest.Name
if ((Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash -ne $actual) { throw "The copied backup checksum does not match" }
Write-Host "Copied verified encrypted backup to $copied"
Write-Host "The backup passphrase was not copied. Keep it separately."
