param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $Destination) { $Destination = Join-Path $taskRepository '.cache/jdbc-drivers' }
$taskDestination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $taskDestination -Force | Out-Null
$taskManifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'jdbc-drivers.json') -Raw | ConvertFrom-Json
foreach ($taskDriver in $taskManifest) {
    if ($taskDriver.file -notmatch '^[a-zA-Z0-9_.-]+[.]jar$' -or $taskDriver.url -notlike 'https://repo.maven.apache.org/maven2/*') {
        throw 'Unexpected pinned JDBC artifact'
    }
    $taskTarget = Join-Path $taskDestination $taskDriver.file
    if (-not (Test-Path -LiteralPath $taskTarget) -or (Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskDriver.sha256) {
        & curl.exe --location --fail --silent --show-error --retry 2 --output $taskTarget $taskDriver.url
        if ($LASTEXITCODE -ne 0) { throw 'Pinned JDBC artifact download failed' }
    }
    if ((Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskDriver.sha256) {
        throw 'Pinned JDBC artifact SHA-256 mismatch'
    }
    Write-Output ('Verified ' + $taskDriver.file)
}
