param(
    [string]$Destination = '',
    [string]$OracleCandidateJar = '',
    [string]$SqlServerCandidateJar = ''
)
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (-not $Destination) { $Destination = Join-Path $taskRepository '.cache/seatunnel-cdc-3.0.0-v2' }
if (-not $OracleCandidateJar) { $OracleCandidateJar = Join-Path $taskRepository '.cache/oracle-cdc/engine-research/connector-cdc-oracle-3.0.0.jar' }
if (-not $SqlServerCandidateJar) { $SqlServerCandidateJar = Join-Path $taskRepository '.cache/sqlserver-cdc-20261114/research/connector-cdc-sqlserver-3.0.0.jar' }
$taskDestination = [IO.Path]::GetFullPath($Destination)
$taskArtifacts = @(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'cdc-artifacts.json') -Raw | ConvertFrom-Json)
$taskPins = @{
    'connector-cdc-oracle' = @{
        Bytes = 31590975
        Sha256 = 'a9333a2f03c9207ba7a5a96cc839831000c000343a50473942338c7686f1df4f'
        Sha1 = 'f33f56cd8ad2625ad91e634b93fe96ef8f24e6a5'
        Candidate = $OracleCandidateJar
    }
    'connector-cdc-sqlserver' = @{
        Bytes = 12484442
        Sha256 = '85bb3d911e5f387e4c5570868914928fc674b9cee2acaee603505450a4b876de'
        Sha1 = '0e06e20e9510915b5e90eabc077bcfb6ce777368'
        Candidate = $SqlServerCandidateJar
    }
}
if ($taskArtifacts.Count -ne 2) { throw 'Expected only the pinned Oracle and SQL Server CDC connectors' }
$taskSeen = @{}
foreach ($taskArtifact in $taskArtifacts) {
    $taskName = ([string]$taskArtifact.coordinate).Replace('org.apache.seatunnel:', '').Replace(':3.0.0', '')
    if (-not $taskPins.ContainsKey($taskName) -or $taskSeen.ContainsKey($taskName)) { throw 'Unexpected or duplicate CDC artifact' }
    $taskPin = $taskPins[$taskName]
    $taskUrl = 'https://repo.maven.apache.org/maven2/org/apache/seatunnel/' + $taskName + '/3.0.0/' + $taskName + '-3.0.0.jar'
    if ($taskArtifact.coordinate -ne ('org.apache.seatunnel:' + $taskName + ':3.0.0') -or
        $taskArtifact.file -ne ($taskName + '-3.0.0.jar') -or
        $taskArtifact.bytes -ne $taskPin.Bytes -or
        $taskArtifact.sha256 -ne $taskPin.Sha256 -or
        $taskArtifact.sha1 -ne $taskPin.Sha1 -or
        $taskArtifact.url -ne $taskUrl -or
        $taskArtifact.sha1Url -ne ($taskUrl + '.sha1')) {
        throw 'Unexpected CDC artifact pin'
    }
    $taskSeen[$taskName] = $true
}
function Assert-CdcArtifact([string]$Path, $Artifact) {
    if ((Get-Item -LiteralPath $Path).Length -ne $Artifact.bytes -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Artifact.sha256 -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant() -ne $Artifact.sha1) {
        throw ('Pinned CDC artifact size or checksum mismatch: ' + $Artifact.file)
    }
}
New-Item -ItemType Directory -Path $taskDestination -Force | Out-Null
foreach ($taskArtifact in $taskArtifacts) {
    $taskName = ([string]$taskArtifact.coordinate).Split(':')[1]
    $taskTarget = Join-Path $taskDestination $taskArtifact.file
    $taskCandidate = $taskPins[$taskName].Candidate
    if (-not (Test-Path -LiteralPath $taskTarget)) {
        if (Test-Path -LiteralPath $taskCandidate) {
            Assert-CdcArtifact $taskCandidate $taskArtifact
            Copy-Item -LiteralPath $taskCandidate -Destination $taskTarget
        } else {
            & curl.exe --location --fail --silent --show-error --retry 2 --connect-timeout 15 --max-time 300 --output $taskTarget $taskArtifact.url
            if ($LASTEXITCODE -ne 0) { throw ('Pinned CDC artifact download failed: ' + $taskArtifact.file) }
        }
    }
    Assert-CdcArtifact $taskTarget $taskArtifact
    Write-Output ('Verified ' + $taskArtifact.coordinate + ' (' + $taskArtifact.bytes + ' bytes, SHA-256 and upstream SHA-1)')
}
