function Use-NimbusJava {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $workspaceRoot = Split-Path -Parent $ProjectRoot
    $bundledJdk = $null
    foreach ($bundledJdkRoot in @(
        (Join-Path $ProjectRoot ".tooling\jdk"),
        (Join-Path $workspaceRoot ".tooling\jdk")
    )) {
        if (-not $bundledJdk -and (Test-Path -LiteralPath $bundledJdkRoot)) {
            $bundledJdk = Get-ChildItem -LiteralPath $bundledJdkRoot -Directory -Filter "jdk-17*" |
                Sort-Object Name -Descending |
                Select-Object -First 1
        }
    }

    if ($bundledJdk) {
        $env:JAVA_HOME = $bundledJdk.FullName
        $env:Path = "$($bundledJdk.FullName)\bin;$env:Path"
    } elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
    } elseif (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
        throw "JDK 17 or newer was not found. Install JDK 17, set JAVA_HOME, and run this command again."
    }

    $javaVersionText = (& java.exe -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "Java is present but could not be started." }
    $versionMatch = [regex]::Match($javaVersionText, 'version\s+"(?:1\.)?(\d+)')
    if (-not $versionMatch.Success -or [int]$versionMatch.Groups[1].Value -lt 17) {
        throw "Nimbus requires JDK 17 or newer. Detected: $($javaVersionText.Trim())"
    }
}
