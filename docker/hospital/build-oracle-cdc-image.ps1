param(
    [string]$Image = 'hospital-openmetadata:2.0.4-integrate-v16-sqlserver-cdc-20261114',
    [Parameter(Mandatory = $true)]
    [Alias('SourceRevision')]
    [string]$Revision,
    [string]$NodeExecutable = 'node',
    [string]$JarExecutable = 'jar',
    [string]$MavenExecutable = 'mvn',
    [string]$SourceDate = '2026-11-14T00:00:00Z',
    [switch]$SkipBackendBuild,
    [switch]$SkipUiBuild,
    [string]$ExpectedServiceSha256,
    [string]$ExpectedDistManifestSha256
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Revision -cnotmatch '^[0-9a-f]{40}$') { throw 'Revision must be exactly 40 lower-case hexadecimal characters' }
if ($Image -cnotmatch '^[a-z0-9]+(?:[._-][a-z0-9]+)*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$') {
    throw 'Image must be a local repository:tag without a registry or path'
}
$archiveDate = [DateTimeOffset]::MinValue
if ($SourceDate -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$' -or
    -not [DateTimeOffset]::TryParseExact($SourceDate, "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$archiveDate) -or
    $archiveDate.Year -lt 1980 -or $archiveDate.Year -gt 2099) {
    throw 'SourceDate must be a valid UTC archive timestamp between 1980 and 2099'
}
if ($SkipBackendBuild -and $ExpectedServiceSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'SkipBackendBuild requires ExpectedServiceSha256 (64 lower-case hex)' }
if ($SkipUiBuild -and $ExpectedDistManifestSha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'SkipUiBuild requires ExpectedDistManifestSha256 (64 lower-case hex)' }
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$productionPaths = @(
    'pom.xml', '.mvn', '.gitattributes', 'common', 'openmetadata-spec', 'openmetadata-sdk', 'openmetadata-shaded-deps',
    'openmetadata-service', 'openmetadata-ui', 'openmetadata-ui-core-components',
    'package.json', 'yarn.lock', 'docker/hospital/Dockerfile.oracle-cdc',
    'docker/hospital/build-oracle-cdc-image.ps1', 'tools/hospital/oracle-cdc-release'
)
function Assert-ReleaseSource {
    $head = & git -C $repository rev-parse --verify HEAD
    if ($LASTEXITCODE -ne 0 -or @($head).Count -ne 1) { throw 'Cannot resolve current git HEAD' }
    if ($Revision -cne $head.Trim()) { throw 'Revision does not match current git HEAD' }
    $changes = & git -C $repository status --porcelain=v1 --untracked-files=all -- @productionPaths
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect production source and release tooling' }
    if (@($changes).Count -ne 0) { throw 'Production source or release tooling has uncommitted changes' }
}
Assert-ReleaseSource
. (Join-Path $repository 'tools/hospital/oracle-cdc-release/ReleaseInputs.ps1')
$repository = Assert-OracleCdcRepositoryPath -Repository $repository -Path $repository
$ui = Assert-OracleCdcRepositoryPath -Repository $repository -Path (Join-Path $repository 'openmetadata-ui/src/main/resources/ui')
$dist = Assert-OracleCdcRepositoryPath -Repository $repository -Path (Join-Path $ui 'dist') -AllowMissing
$serviceJar = Assert-OracleCdcRepositoryPath -Repository $repository -Path (Join-Path $repository 'openmetadata-service/target/openmetadata-service-2.0.4.jar') -AllowMissing
if ($SkipBackendBuild) {
    $serviceJar = Assert-OracleCdcRepositoryPath -Repository $repository -Path $serviceJar
    if ((Get-OracleCdcFileSha256 -Path $serviceJar) -cne $ExpectedServiceSha256) {
        throw 'Service archive SHA256 does not match ExpectedServiceSha256'
    }
}
if ($SkipUiBuild) {
    $distManifest = Get-OracleCdcDistManifest -Repository $repository -Dist $dist
    if ($distManifest.sha256 -cne $ExpectedDistManifestSha256) { throw 'Production dist fingerprint does not match ExpectedDistManifestSha256' }
}
if (-not $SkipBackendBuild) {
    Push-Location $repository
    try {
        & $MavenExecutable --offline --no-transfer-progress -pl openmetadata-service -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }
    } finally { Pop-Location }
}
if (-not $SkipUiBuild) {
    Push-Location $ui
    try {
        & $NodeExecutable --max-old-space-size=8192 node_modules/vite/bin/vite.js build
        if ($LASTEXITCODE -ne 0) { throw 'Production UI build failed' }
    } finally { Pop-Location }
}
Assert-ReleaseSource
$serviceJar = Assert-OracleCdcRepositoryPath -Repository $repository -Path $serviceJar
$serviceHash = Get-OracleCdcFileSha256 -Path $serviceJar
if ($SkipBackendBuild -and $serviceHash -cne $ExpectedServiceSha256) { throw 'Service archive changed after input verification' }
$distManifest = Get-OracleCdcDistManifest -Repository $repository -Dist $dist
if ($SkipUiBuild -and $distManifest.sha256 -cne $ExpectedDistManifestSha256) { throw 'Production dist changed after input verification' }
$buildId = [Guid]::NewGuid().ToString('N')
$release = Assert-OracleCdcRepositoryPath -Repository $repository -Path (Join-Path $repository ('.cache/oracle-cdc/release/' + $buildId)) -AllowMissing
$staging = Join-Path $release 'stage'
$assets = Join-Path $staging 'assets'
$context = Join-Path $release 'context'
foreach ($directory in @($assets, $context)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
foreach ($file in $distManifest.files) {
    $source = Assert-OracleCdcRepositoryPath -Repository $repository -Path (Join-Path $dist $file.path)
    $destination = Assert-OracleCdcRepositoryPath -Repository $repository -Path (Join-Path $assets $file.path) -AllowMissing
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    [IO.File]::Copy($source, $destination, $false)
}
$stagedManifest = Get-OracleCdcDistManifest -Repository $repository -Dist $assets
if ($stagedManifest.sha256 -cne $distManifest.sha256) { throw 'Staged production dist differs from verified inputs' }
$contextService = Join-Path $context 'openmetadata-service-2.0.4.jar'
[IO.File]::Copy($serviceJar, $contextService, $false)
if ((Get-OracleCdcFileSha256 -Path $contextService) -cne $serviceHash) { throw 'Staged service archive differs from verified inputs' }
$serviceArchive = [IO.Compression.ZipFile]::OpenRead($contextService)
try {
    if ($null -eq $serviceArchive.GetEntry('org/openmetadata/service/OpenMetadataApplication.class')) { throw 'Service archive is missing OpenMetadataApplication.class' }
} finally { $serviceArchive.Dispose() }
$uiJar = Join-Path $context 'openmetadata-ui-2.0.4.jar'
$archiveArguments = [Collections.Generic.List[string]]::new()
$archiveArguments.AddRange([string[]]@('--create', '--file', $uiJar, '--date', $SourceDate, '--no-manifest'))
foreach ($file in $distManifest.files) { $archiveArguments.AddRange([string[]]@('-C', $staging, ('assets/' + $file.path))) }
$argumentFile = Join-Path $release 'ui-archive.args'
$quotedArguments = foreach ($argument in $archiveArguments) { '"' + $argument.Replace('\', '/').Replace('"', '\"') + '"' }
[IO.File]::WriteAllText($argumentFile, ($quotedArguments -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
& $JarExecutable ('@' + $argumentFile)
if ($LASTEXITCODE -ne 0) { throw 'Production UI archive packaging failed' }
$uiArchive = [IO.Compression.ZipFile]::OpenRead($uiJar)
try {
    if ($null -eq $uiArchive.GetEntry('assets/index.html') -or $uiArchive.Entries.Count -ne $distManifest.files.Count) {
        throw 'UI archive must contain exactly the verified production assets, including assets/index.html'
    }
    foreach ($file in $distManifest.files) {
        $entry = $uiArchive.GetEntry('assets/' + $file.path)
        if ($null -eq $entry -or $entry.Length -ne $file.length) { throw 'UI archive differs from the verified production dist' }
        $stream = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose(); $stream.Dispose() }
        if ($hash -cne $file.sha256) { throw 'UI archive differs from the verified production dist' }
    }
} finally { $uiArchive.Dispose() }
$uiHash = Get-OracleCdcFileSha256 -Path $uiJar
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $context '.dockerignore'), "*`n!openmetadata-service-2.0.4.jar`n!openmetadata-ui-2.0.4.jar`n", $utf8)
[IO.File]::WriteAllText((Join-Path $release 'dist-manifest.tsv'), $distManifest.canonical, $utf8)
Assert-ReleaseSource
$baseImage = 'harbor.qcrmyy.local/coop/hospital-openmetadata@sha256:f9fac7c3c8230093c501e9730be4486234fd17e1e498b928fc9e36b9f559b518'
$localBaseId = & docker image inspect $baseImage --format '{{.Id}}'
if ($LASTEXITCODE -ne 0 -or @($localBaseId).Count -ne 1 -or $localBaseId -cnotmatch '^sha256:[0-9a-f]{64}$') {
    throw 'The exact baseline image must already be present locally; this builder never pulls it'
}
# The legacy builder consumes the preloaded digest without BuildKit registry metadata resolution.
$previousBuildKit = [Environment]::GetEnvironmentVariable('DOCKER_BUILDKIT', 'Process')
try {
    [Environment]::SetEnvironmentVariable('DOCKER_BUILDKIT', '0', 'Process')
    & docker build --pull=false --network=none --file (Join-Path $PSScriptRoot 'Dockerfile.oracle-cdc') --build-arg "SOURCE_REVISION=$Revision" --build-arg "SERVICE_SHA256=$serviceHash" --build-arg "UI_SHA256=$uiHash" --tag $Image $context
    if ($LASTEXITCODE -ne 0) { throw 'Oracle CDC application image build failed' }
} finally {
    if ($null -eq $previousBuildKit) { Remove-Item -LiteralPath Env:DOCKER_BUILDKIT -ErrorAction SilentlyContinue }
    else { [Environment]::SetEnvironmentVariable('DOCKER_BUILDKIT', $previousBuildKit, 'Process') }
}
$imageId = & docker image inspect $Image --format '{{.Id}}'
if ($LASTEXITCODE -ne 0 -or @($imageId).Count -ne 1 -or $imageId -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Cannot inspect the actual local image ID' }
$manifest = [ordered]@{
    image = $Image
    imageId = $imageId.Trim()
    sourceRevision = $Revision
    baseImage = $baseImage
    serviceArchiveSha256 = $serviceHash
    uiArchiveSha256 = $uiHash
    productionDistManifestSha256 = $distManifest.sha256
    productionDistManifestAlgorithm = $distManifest.algorithm
    productionDistManifest = (Join-Path $release 'dist-manifest.tsv')
    context = $context
    archiveDate = $SourceDate
    createdAt = [DateTimeOffset]::UtcNow.ToString('o')
}
$manifestPath = Join-Path $release 'image-manifest.json'
$manifest['manifestPath'] = $manifestPath
$json = $manifest | ConvertTo-Json
[IO.File]::WriteAllText($manifestPath, $json + "`n", $utf8)
$json
