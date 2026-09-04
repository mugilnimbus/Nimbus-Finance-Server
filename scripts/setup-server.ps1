param(
    [string]$TailscaleAuthKey = "",
    [switch]$StartTailscale,
    [switch]$StartInference,
    [switch]$ReplaceTailscaleKey
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
# Check prerequisites before creating configuration or asking for credentials.
if ($PSVersionTable.PSVersion.Major -lt 7) { throw "Open PowerShell 7, then run setup again." }
if (-not $IsWindows) { throw "This setup helper currently supports Windows. See README.md for platform requirements." }
foreach ($command in @('git', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Install $command, reopen PowerShell, then run setup again." }
}
. (Join-Path $PSScriptRoot "use-java.ps1")
Use-NimbusJava -ProjectRoot $projectRoot
$dockerOs = & docker info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0) { throw "Start Docker Desktop with Linux containers, then run setup again." }
if (($dockerOs | Out-String).Trim() -ne 'linux') { throw "Switch Docker Desktop to Linux containers, then run setup again." }
$secretDirectory = Join-Path $projectRoot ".secrets"
$backupDirectory = Join-Path $projectRoot "data\backups"
$modelDirectory = Join-Path $projectRoot "models"
$inferenceConfigDirectory = Join-Path $projectRoot "data\inference"
$modelCacheDirectory = Join-Path $projectRoot "data\model-cache"
New-Item -ItemType Directory -Force -Path $secretDirectory, $backupDirectory, $modelDirectory, $inferenceConfigDirectory, $modelCacheDirectory | Out-Null

function New-RandomSecret([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    [Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function Ensure-Secret([string]$name) {
    $path = Join-Path $secretDirectory $name
    if (-not (Test-Path -LiteralPath $path)) {
        [IO.File]::WriteAllText($path, (New-RandomSecret), [Text.UTF8Encoding]::new($false))
    }
}

function Set-EnvironmentValue([string]$path, [string]$name, [string]$value) {
    $lines = [Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $path) { $lines.AddRange([string[]][IO.File]::ReadAllLines($path)) }
    $replacement = "$name=$value"
    $changed = $true
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index] -match "^$([regex]::Escape($name))=") {
            $changed = $lines[$index] -ne $replacement
            $lines[$index] = $replacement
            if ($changed) { [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false)) }
            return $changed
        }
    }
    $lines.Add($replacement)
    [IO.File]::WriteAllLines($path, $lines, [Text.UTF8Encoding]::new($false))
    return $true
}

Ensure-Secret "postgres_password"
Ensure-Secret "finance_admin_key"
Ensure-Secret "backup_passphrase"

$tailscalePath = Join-Path $secretDirectory "tailscale_auth_key"
if ($TailscaleAuthKey) {
    [IO.File]::WriteAllText($tailscalePath, $TailscaleAuthKey.Trim(), [Text.UTF8Encoding]::new($false))
} elseif (-not (Test-Path -LiteralPath $tailscalePath)) {
    [IO.File]::WriteAllText($tailscalePath, "", [Text.UTF8Encoding]::new($false))
}
$storedTailscaleKey = [IO.File]::ReadAllText($tailscalePath).Trim()
if ($StartTailscale -and ($ReplaceTailscaleKey -or [string]::IsNullOrWhiteSpace($storedTailscaleKey))) {
    $secureKey = Read-Host "Paste the one-time Tailscale auth key" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if (-not $plainKey.Trim()) { throw "A Tailscale auth key is required" }
        [IO.File]::WriteAllText($tailscalePath, $plainKey.Trim(), [Text.UTF8Encoding]::new($false))
    } finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $plainKey = $null
    }
}
$storedTailscaleKey = $null

$envPath = Join-Path $projectRoot ".env"
if (-not (Test-Path -LiteralPath $envPath)) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ".env.example") -Destination $envPath
}
Set-EnvironmentValue $envPath "POSTGRES_BASE_IMAGE" "alpine:3.23.3@sha256:25109184c71bdad752c8312a8623239686a9a2071e8825f20acb8f2198c3f659" | Out-Null
Set-EnvironmentValue $envPath "BACKUP_BASE_IMAGE" "alpine:3.23.3@sha256:25109184c71bdad752c8312a8623239686a9a2071e8825f20acb8f2198c3f659" | Out-Null
Set-EnvironmentValue $envPath "SERVER_BASE_IMAGE" "eclipse-temurin:17-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a" | Out-Null
Set-EnvironmentValue $envPath "TAILSCALE_IMAGE" "tailscale/tailscale:stable@sha256:8c42c4574ab066384fcb72f69e086a2ff1dd3652eb6f56856cee34bcf0d2f680" | Out-Null
Set-EnvironmentValue $envPath "INFERENCE_IMAGE" "ghcr.io/ggml-org/llama.cpp:server-cuda-b10666@sha256:a2d04d1d1c2b2abe287fef9a22a3700a7fa20aec4c4ab56135e0099f38119848" | Out-Null
# The dashboard owns model downloads and selection; an empty model library is valid.
$revision = (git -C $projectRoot rev-parse --short=12 HEAD 2>$null)
if (-not $revision) { $revision = "working-tree" }
elseif (git -C $projectRoot status --porcelain) { $revision = "$revision-dirty" }
$buildTime = [DateTimeOffset]::UtcNow.ToString("o")
Set-EnvironmentValue $envPath "BUILD_REVISION" $revision | Out-Null
Set-EnvironmentValue $envPath "BUILD_TIME" $buildTime | Out-Null

Push-Location $projectRoot
try {
    & .\gradlew.bat :server:test :server:buildFatJar --dependency-verification=strict
    if ($LASTEXITCODE -ne 0) { throw "Server build failed" }
    $composeProfiles = @()
    if ($StartTailscale) { $composeProfiles += @("--profile", "tailscale") }
    if ($StartInference) { $composeProfiles += @("--profile", "inference") }
    docker compose @composeProfiles up -d --build
    if ($LASTEXITCODE -ne 0) { throw "Docker startup failed" }
    if ($StartTailscale) {
        # Re-run containerboot so a newly enabled tailnet HTTPS certificate setting
        # is applied even when the existing sidecar configuration did not change.
        docker compose --profile tailscale restart tailscale
        if ($LASTEXITCODE -ne 0) { throw "Tailscale restart failed" }
    }
    Write-Host "Nimbus server setup completed."
    Write-Host "Local computer API: http://127.0.0.1:8080"
    if ($StartTailscale) {
        $serverAddress = $null
        $addressError = "Tailscale did not report a usable address."
        1..30 | ForEach-Object {
            if (-not $serverAddress) {
                try {
                    $serverAddress = & .\scripts\get-server-address.ps1
                } catch {
                    $addressError = $_.Exception.Message
                    Start-Sleep -Seconds 2
                }
            }
        }
        if (-not $serverAddress) {
            throw "The local server is running, but private HTTPS is not ready. $addressError"
        }
        if (Set-EnvironmentValue $envPath "PUBLIC_SERVER_URL" $serverAddress) {
            docker compose @composeProfiles up -d --force-recreate finance-api
            if ($LASTEXITCODE -ne 0) { throw "The server address was found, but the dashboard API restart failed" }
            $apiReady = $false
            1..30 | ForEach-Object {
                if (-not $apiReady) {
                    try {
                        $readyResponse = Invoke-RestMethod -Uri "http://127.0.0.1:8080/health/ready" -TimeoutSec 2
                        $apiReady = $readyResponse.status -eq "ready"
                    } catch {
                        Start-Sleep -Seconds 1
                    }
                }
            }
            if (-not $apiReady) { throw "The dashboard API restarted but did not become ready" }
        }
        Write-Host "Phone server address: $serverAddress"
        Write-Host "Owner dashboard: run .\scripts\open-dashboard.ps1"
    } else {
        Write-Host "For private phone access, run .\scripts\setup-server.ps1 -StartTailscale"
    }
    if ($StartInference) {
        Write-Host "Inference service started. Select and download a model in the owner dashboard; phone AI is ready only after the model loads."
    } else {
        Write-Host "For server inference, configure a model in the owner dashboard and rerun with -StartTailscale -StartInference (NVIDIA Docker GPU support required)."
    }
} finally {
    Pop-Location
}
