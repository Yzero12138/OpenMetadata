param(
    [string]$Destination = '',
    [string]$CandidateJar = ''
)
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $Destination) { $Destination = Join-Path $taskRepository '.cache/seatunnel-oracle-cdc-3.0.0' }
if (-not $CandidateJar) { $CandidateJar = Join-Path $taskRepository '.cache/oracle-cdc/engine-research/connector-cdc-oracle-3.0.0.jar' }
$taskDestination = [IO.Path]::GetFullPath($Destination)
$taskArtifacts = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'oracle-cdc-artifacts.json') -Raw | ConvertFrom-Json)
if ($taskArtifacts.Count -ne 1) { throw 'Expected only the pinned Oracle CDC connector' }
$taskArtifact = $taskArtifacts[0]
if ($taskArtifact.coordinate -ne 'org.apache.seatunnel:connector-cdc-oracle:3.0.0' -or
    $taskArtifact.file -ne 'connector-cdc-oracle-3.0.0.jar' -or
    $taskArtifact.bytes -ne 31590975 -or
    $taskArtifact.sha256 -ne 'a9333a2f03c9207ba7a5a96cc839831000c000343a50473942338c7686f1df4f' -or
    $taskArtifact.sha1 -ne 'f33f56cd8ad2625ad91e634b93fe96ef8f24e6a5' -or
    $taskArtifact.url -ne 'https://repo.maven.apache.org/maven2/org/apache/seatunnel/connector-cdc-oracle/3.0.0/connector-cdc-oracle-3.0.0.jar') {
    throw 'Unexpected Oracle CDC artifact pin'
}
function Assert-OracleCdcArtifact([string]$Path) {
    if ((Get-Item -LiteralPath $Path).Length -ne $taskArtifact.bytes -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskArtifact.sha256 -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant() -ne $taskArtifact.sha1) {
        throw 'Pinned Oracle CDC artifact size or checksum mismatch'
    }
}
New-Item -ItemType Directory -Path $taskDestination -Force | Out-Null
$taskTarget = Join-Path $taskDestination $taskArtifact.file
if (-not (Test-Path -LiteralPath $taskTarget)) {
    if (Test-Path -LiteralPath $CandidateJar) {
        Assert-OracleCdcArtifact $CandidateJar
        Copy-Item -LiteralPath $CandidateJar -Destination $taskTarget
    } else {
        & curl.exe --location --fail --silent --show-error --retry 2 --connect-timeout 15 --max-time 300 --output $taskTarget $taskArtifact.url
        if ($LASTEXITCODE -ne 0) { throw 'Pinned Oracle CDC artifact download failed' }
    }
}
Assert-OracleCdcArtifact $taskTarget
Write-Output ('Verified ' + $taskArtifact.coordinate + ' (31590975 bytes, SHA-256 and upstream SHA-1)')
