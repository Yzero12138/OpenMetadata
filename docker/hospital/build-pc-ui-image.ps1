param(
    [string]$Image = 'hospital-openmetadata:2.0.4-integrate-v7-pc',
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$Revision,
    [string]$NodeExecutable = 'node',
    [string]$JarExecutable = 'jar',
    [string]$SourceDate = '2026-10-10T00:00:00Z',
    [switch]$SkipUiBuild
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$ui = Join-Path $repository 'openmetadata-ui/src/main/resources/ui'
$dist = Join-Path $ui 'dist'
if (-not $SkipUiBuild) {
    Push-Location $ui
    try {
        & $NodeExecutable --max-old-space-size=8192 node_modules/vite/bin/vite.js build
        if ($LASTEXITCODE -ne 0) { throw 'Production UI build failed' }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) {
    throw 'Build the production UI before packaging. SkipUiBuild accepts only previously verified dist assets.'
}
$bundle = Join-Path $repository '.cache/hospital-pc-ui'
$buildId = [Guid]::NewGuid().ToString('N')
$staging = Join-Path $bundle ('stage-' + $buildId)
$context = Join-Path $bundle ('context-' + $buildId)
New-Item -ItemType Directory -Path $staging, $context -Force | Out-Null
Copy-Item -LiteralPath $dist -Destination (Join-Path $staging 'assets') -Recurse
$uiJar = Join-Path $context 'openmetadata-ui-2.0.4.jar'
& $JarExecutable --create --file $uiJar --date $SourceDate -C $staging assets
if ($LASTEXITCODE -ne 0) { throw 'UI archive packaging failed' }
$entries = & $JarExecutable tf $uiJar
if ($LASTEXITCODE -ne 0 -or 'assets/index.html' -notin $entries) {
    throw 'The UI archive is missing assets/index.html'
}
& docker build --network=none --pull=false --file (Join-Path $PSScriptRoot 'Dockerfile.pc-ui') --build-arg "SOURCE_REVISION=$Revision" --tag $Image $context
if ($LASTEXITCODE -ne 0) { throw 'PC UI image build failed' }
$imageId = & docker image inspect $Image --format '{{.Id}}'
if ($LASTEXITCODE -ne 0) { throw 'PC UI image inspection failed' }
$manifest = [ordered]@{
    image = $Image
    imageId = $imageId
    sourceRevision = $Revision
    baseImage = 'harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:934be97369255e2b08bd803fb719b0de0112ff8ba0c739e90ee3d2212f41a473'
    uiArchiveSha256 = (Get-FileHash -LiteralPath $uiJar -Algorithm SHA256).Hash.ToLowerInvariant()
    archiveDate = $SourceDate
    context = $context
    createdAt = [DateTimeOffset]::UtcNow.ToString('o')
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $bundle 'image-manifest.json') -Encoding utf8
$manifest | ConvertTo-Json
