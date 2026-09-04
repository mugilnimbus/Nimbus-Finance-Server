$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Push-Location $projectRoot
try {
    $containerId = (docker compose --profile tailscale ps --status running -q tailscale 2>$null | Out-String).Trim()
    if (-not $containerId) {
        throw "The Tailscale server is not running. Run .\scripts\setup-server.ps1 -StartTailscale first."
    }

    # The sidecar filesystem is read-only, so containerboot cannot create the
    # conventional /var/run/tailscale socket symlink. Always address the real
    # userspace socket to keep status checks bounded and non-interactive.
    $rawStatus = (docker compose --profile tailscale exec -T tailscale tailscale --socket=/tmp/tailscaled.sock status --json 2>$null | Out-String).Trim()
    if (-not $rawStatus) { throw "Tailscale is starting or not authenticated yet. Wait a few seconds and try again." }
    $status = $rawStatus | ConvertFrom-Json
    if ($status.BackendState -ne "Running") {
        throw "Tailscale is not ready (state: $($status.BackendState)). Check the auth key and container logs."
    }

    $dnsName = ([string]$status.Self.DNSName).Trim().TrimEnd(".")
    if (-not $dnsName) { throw "Tailscale did not report a MagicDNS name. Enable MagicDNS and HTTPS certificates in the Tailscale admin console." }

    $rawServeStatus = (docker compose --profile tailscale exec -T tailscale tailscale --socket=/tmp/tailscaled.sock serve status --json 2>$null | Out-String).Trim()
    if (-not $rawServeStatus) { throw "Tailscale is connected, but its HTTPS proxy is not configured." }
    $serveStatus = $rawServeStatus | ConvertFrom-Json
    if (@($serveStatus.PSObject.Properties).Count -eq 0) {
        throw "Tailscale is connected, but HTTPS Serve is not ready. Enable HTTPS certificates at https://login.tailscale.com/admin/dns, then rerun .\scripts\setup-server.ps1 -StartTailscale."
    }
    Write-Output "https://$dnsName"
} finally {
    Pop-Location
}
