param(
    [string]$Image = 'hospital-openmetadata:2.0.4-integrate-v11-doris',
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$Revision,
    [string]$NodeExecutable = 'node',
    [string]$JarExecutable = 'jar',
    [string]$MavenExecutable = 'mvn',
    [string]$SourceDate = '2026-10-20T00:00:00Z',
    [switch]$SkipBackendBuild,
    [switch]$SkipUiBuild
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $SkipBackendBuild) {
    Push-Location $repository
    try {
        & $MavenExecutable --no-transfer-progress -pl openmetadata-service -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }
    } finally { Pop-Location }
}
$ui = Join-Path $repository 'openmetadata-ui/src/main/resources/ui'
if (-not $SkipUiBuild) {
    Push-Location $ui
    try {
        & $NodeExecutable --max-old-space-size=8192 node_modules/vite/bin/vite.js build
        if ($LASTEXITCODE -ne 0) { throw 'Production UI build failed' }
    } finally { Pop-Location }
}
$serviceJar = Join-Path $repository 'openmetadata-service/target/openmetadata-service-2.0.4.jar'
$dist = Join-Path $ui 'dist'
if (-not (Test-Path -LiteralPath $serviceJar) -or -not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) {
    throw 'Both verified backend and production UI archives are required'
}
$bundle = Join-Path $repository '.cache/doris/app-build'
$buildId = [Guid]::NewGuid().ToString('N')
$staging = Join-Path $bundle ('stage-' + $buildId)
$context = Join-Path $bundle ('context-' + $buildId)
New-Item -ItemType Directory -Path $staging, $context -Force | Out-Null
Copy-Item -LiteralPath $dist -Destination (Join-Path $staging 'assets') -Recurse
$uiJar = Join-Path $context 'openmetadata-ui-2.0.4.jar'
& $JarExecutable --create --file $uiJar --date $SourceDate -C $staging assets
if ($LASTEXITCODE -ne 0) { throw 'UI archive packaging failed' }
$entries = & $JarExecutable tf $uiJar
if ($LASTEXITCODE -ne 0 -or 'assets/index.html' -notin $entries) { throw 'UI archive is incomplete' }
Copy-Item -LiteralPath $serviceJar -Destination $context
$serviceHash = (Get-FileHash -LiteralPath $serviceJar -Algorithm SHA256).Hash.ToLowerInvariant()
$uiHash = (Get-FileHash -LiteralPath $uiJar -Algorithm SHA256).Hash.ToLowerInvariant()
& docker build --network=none --pull=false --file (Join-Path $PSScriptRoot 'Dockerfile.doris') --build-arg "SOURCE_REVISION=$Revision" --build-arg "SERVICE_SHA256=$serviceHash" --build-arg "UI_SHA256=$uiHash" --tag $Image $context
if ($LASTEXITCODE -ne 0) { throw 'Doris application image build failed' }
$imageId = & docker image inspect $Image --format '{{.Id}}'
if ($LASTEXITCODE -ne 0) { throw 'Image inspection failed' }
$manifest = [ordered]@{
    image = $Image
    imageId = $imageId
    sourceRevision = $Revision
    baseImage = 'harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:287323f97a2643a6c009f977eca3d438a83dcde0fa6c28b922c09287fcb1a2e1'
    serviceArchiveSha256 = $serviceHash
    uiArchiveSha256 = $uiHash
    context = $context
    archiveDate = $SourceDate
    createdAt = [DateTimeOffset]::UtcNow.ToString('o')
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $bundle 'image-manifest.json') -Encoding utf8
$manifest | ConvertTo-Json
