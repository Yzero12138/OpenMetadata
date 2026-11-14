param([switch]$CheckArchivePackaging, [switch]$CheckSessionIsolation, [string]$JarExecutable = 'jar')
$savedLastExitCode = Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
$hadLastExitCode = $null -ne $savedLastExitCode
$savedLastExitCodeValue = if ($hadLastExitCode) { $savedLastExitCode.Value } else { $null }
try {
$ErrorActionPreference = 'Stop'
if ($CheckSessionIsolation) {
    foreach ($preexisting in @($false, $true)) {
        foreach ($failure in @($false, $true)) {
            $session = [PowerShell]::Create()
            try {
                $null = $session.AddScript({
                    param($TestScript, $Preexisting, $Failure)
                    $ErrorActionPreference = 'Stop'
                    if ($Preexisting) {
                        function global:docker { 'operator-docker-function' }
                        function global:OracleCdcBlockedTool { 'operator-blocked-function' }
                        $global:OracleCdcUnexpectedCalls = 137
                        $global:OracleCdcFixtureContexts = [pscustomobject]@{ identity = 'operator-context' }
                        $global:LASTEXITCODE = 73
                    }
                    $functions = @{ docker = ${function:global:docker}; OracleCdcBlockedTool = ${function:global:OracleCdcBlockedTool} }
                    $variables = @{}
                    foreach ($name in @('OracleCdcUnexpectedCalls','OracleCdcFixtureContexts','LASTEXITCODE')) {
                        $item = Get-Variable -Name $name -Scope Global -ErrorAction SilentlyContinue
                        $variables[$name] = @{ present = ($null -ne $item); value = $(if ($item) { $item.Value }) }
                    }
                    $caught = $null
                    try { & $TestScript -CheckArchivePackaging:$Failure -JarExecutable OracleCdcBlockedTool | Out-Null }
                    catch { $caught = $_.Exception.Message }
                    if ($Failure -and $caught -notlike '*Unexpected external build tool invocation*') { throw "Expected controlled test failure, got: $caught" }
                    if (-not $Failure -and $caught) { throw "Expected successful test exit, got: $caught" }
                    $afterFunctions = @{ docker = ${function:global:docker}; OracleCdcBlockedTool = ${function:global:OracleCdcBlockedTool} }
                    foreach ($name in $functions.Keys) {
                        if (($null -eq $functions[$name]) -ne ($null -eq $afterFunctions[$name]) -or [string]$functions[$name] -cne [string]$afterFunctions[$name]) {
                            throw "Global function binding changed: $name"
                        }
                    }
                    foreach ($name in $variables.Keys) {
                        $item = Get-Variable -Name $name -Scope Global -ErrorAction SilentlyContinue
                        if ($variables[$name].present -ne ($null -ne $item)) { throw "Global variable existence changed: $name" }
                        if ($item -and $item.Value -cne $variables[$name].value) { throw "Global variable value changed: $name" }
                    }
                    "PASS: same-session globals preserved; preexisting=$Preexisting failure=$Failure"
                }).AddArgument($PSCommandPath).AddArgument($preexisting).AddArgument($failure)
                $results = $session.Invoke()
                if ($results.Count -ne 1 -or [string]$results[0] -notlike 'PASS: same-session globals preserved;*') { throw 'Session isolation did not return its verified result' }
                $results | Write-Output
            } finally { $session.Dispose() }
        }
    }
    return
}
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$builder = Join-Path $repository 'docker/hospital/build-oracle-cdc-image.ps1'
if (-not (Test-Path -LiteralPath $builder)) { throw 'FAIL: release builder is not available' }
$fixture = Join-Path $repository ('.cache/oracle-cdc/release/guard-tests-' + [Guid]::NewGuid().ToString('N'))
$fixtureTools = Join-Path $fixture 'tools/hospital/oracle-cdc-release'
$fixtureDocker = Join-Path $fixture 'docker/hospital'
$fixtureUi = Join-Path $fixture 'openmetadata-ui/src/main/resources/ui'
$fixtureDist = Join-Path $fixtureUi 'dist'
$fixtureService = Join-Path $fixture 'openmetadata-service/target/openmetadata-service-2.0.4.jar'
foreach ($directory in @($fixtureTools, $fixtureDocker, $fixtureDist, (Split-Path $fixtureService))) {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
}
Copy-Item -LiteralPath $builder -Destination $fixtureDocker
Copy-Item -LiteralPath (Join-Path $repository 'docker/hospital/Dockerfile.oracle-cdc') -Destination $fixtureDocker
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'ReleaseInputs.ps1') -Destination $fixtureTools
[IO.File]::WriteAllText((Join-Path $fixture '.gitignore'), "target/`ndist/`n.cache/`n")
[IO.File]::WriteAllText((Join-Path $fixture 'openmetadata-service/pom.xml'), '<project/>')
[IO.File]::WriteAllText((Join-Path $fixtureDist 'index.html'), '<html><script type="module" src="/assets/app.js"></script></html>')
[IO.Directory]::CreateDirectory((Join-Path $fixtureDist 'assets')) | Out-Null
[IO.File]::WriteAllText((Join-Path $fixtureDist 'assets/app.js'), 'window.production = true;')
[IO.File]::WriteAllBytes($fixtureService, [byte[]](1, 2, 3, 4))
& git init --quiet -- $fixture
if ($LASTEXITCODE -ne 0) { throw 'Fixture git init failed' }
& git -C $fixture config core.autocrlf false
if ($LASTEXITCODE -ne 0) { throw 'Fixture git configuration failed' }
& git -C $fixture add --all
if ($LASTEXITCODE -ne 0) { throw 'Fixture git add failed' }
& git -C $fixture -c user.name=ReleaseGuardTest -c user.email=release-guard@example.invalid -c commit.gpgsign=false -c core.hooksPath=.git/hooks commit --quiet -m fixture
if ($LASTEXITCODE -ne 0) { throw 'Fixture git commit failed' }
$revision = (& git -C $fixture rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Fixture revision failed' }
. (Join-Path $fixtureTools 'ReleaseInputs.ps1')
$serviceHash = (Get-FileHash -LiteralPath $fixtureService -Algorithm SHA256).Hash.ToLowerInvariant()
$distHash = (Get-OracleCdcDistManifest -Repository $fixture -Dist $fixtureDist).sha256
[IO.File]::WriteAllText((Join-Path $fixtureDist 'index.html'), '<html><script type="module" src="${basePath}assets/app.js" nonce="${cspNonce}"></script></html>')
$realTemplate = Get-OracleCdcDistManifest -Repository $fixture -Dist $fixtureDist
if ($realTemplate.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'FAIL: actual production basePath template was not accepted' }
$distHash = $realTemplate.sha256
Write-Output 'PASS: actual production basePath template'
$mockState = [pscustomobject]@{ unexpectedCalls = 0; contexts = [Collections.Generic.List[string]]::new() }
Set-Item -LiteralPath Function:script:OracleCdcBlockedTool -Value ({
    $mockState.unexpectedCalls++
    throw 'Unexpected external build tool invocation'
}.GetNewClosure())
Set-Item -LiteralPath Function:script:docker -Value ({
    $mockState.unexpectedCalls++
    throw 'Unexpected Docker invocation'
}.GetNewClosure())
$fixtureBuilder = Join-Path $fixtureDocker 'build-oracle-cdc-image.ps1'
$arguments = @{
    Revision = $revision
    NodeExecutable = 'OracleCdcBlockedTool'
    JarExecutable = 'OracleCdcBlockedTool'
    MavenExecutable = 'OracleCdcBlockedTool'
    SkipBackendBuild = $true
    SkipUiBuild = $true
    ExpectedServiceSha256 = $serviceHash
    ExpectedDistManifestSha256 = $distHash
}
$passed = 0
function Assert-Rejected([string]$Name, [string]$Message, [hashtable]$Overrides = @{}) {
    $callArguments = $arguments.Clone()
    foreach ($key in $Overrides.Keys) { $callArguments[$key] = $Overrides[$key] }
    $caught = $null
    try { & $fixtureBuilder @callArguments | Out-Null } catch { $caught = $_.Exception.Message }
    if (-not $caught -or $caught -notlike "*$Message*") {
        throw "FAIL: $Name; expected '$Message', got '$caught'"
    }
    if ($mockState.unexpectedCalls -ne 0) { throw "FAIL: $Name invoked a build tool" }
    $script:passed++
    Write-Output "PASS: $Name"
}
Assert-Rejected 'malformed revision' 'Revision must be exactly 40 lower-case hexadecimal characters' @{ Revision = ('a' * 39) }
Assert-Rejected 'revision differs from HEAD' 'Revision does not match current git HEAD' @{ Revision = ('b' * 40) }
Assert-Rejected 'skip requires service proof' 'SkipBackendBuild requires ExpectedServiceSha256' @{ ExpectedServiceSha256 = '' }
Assert-Rejected 'skip requires dist proof' 'SkipUiBuild requires ExpectedDistManifestSha256' @{ ExpectedDistManifestSha256 = '' }
Assert-Rejected 'stale service proof' 'Service archive SHA256 does not match ExpectedServiceSha256' @{ ExpectedServiceSha256 = ('0' * 64) }
[IO.File]::AppendAllText((Join-Path $fixtureDist 'assets/app.js'), ' window.changed = true;')
Assert-Rejected 'stale production dist proof' 'Production dist fingerprint does not match ExpectedDistManifestSha256'
[IO.File]::WriteAllText((Join-Path $fixtureDist 'assets/app.js'), 'window.fixture = "synthetic-ui-review";')
Assert-Rejected 'fixture identity in production assets' 'Fixture identity synthetic-ui-review is forbidden in production dist'
$appPath = Join-Path $fixtureDist 'assets/app.js'
$rawMarker = [Text.Encoding]::ASCII.GetBytes('SyNtHeTiC-Ui-ReViEw')
foreach ($prefix in @([byte[]](255, 254), [byte[]](254, 255))) {
    [IO.File]::WriteAllBytes($appPath, [byte[]]($prefix + $rawMarker))
    Assert-Rejected ('BOM-prefixed raw ASCII marker ' + [BitConverter]::ToString($prefix)) 'Fixture identity synthetic-ui-review is forbidden in production dist'
}
[IO.File]::WriteAllBytes($appPath, [byte[]]([byte[]](255, 254) + [Text.Encoding]::ASCII.GetBytes('x' * 65528) + $rawMarker))
Assert-Rejected 'BOM-prefixed raw marker spanning scan chunks' 'Fixture identity synthetic-ui-review is forbidden in production dist'
foreach ($encoding in @([Text.Encoding]::Unicode, [Text.Encoding]::BigEndianUnicode)) {
    [IO.File]::WriteAllBytes($appPath, [byte[]]($encoding.GetPreamble() + $encoding.GetBytes('prefix synthetic-ui-review suffix')))
    Assert-Rejected ('valid UTF16 marker ' + $encoding.WebName) 'Fixture identity synthetic-ui-review is forbidden in production dist'
}
[IO.File]::WriteAllText((Join-Path $fixtureDist 'assets/app.js'), 'window.production = true;')
$markerName = Join-Path $fixtureDist 'assets/synthetic-ui-review.js'
[IO.File]::WriteAllText($markerName, 'window.fixture = true;')
Assert-Rejected 'fixture identity in asset filename' 'Fixture identity synthetic-ui-review is forbidden in production dist'
[IO.File]::Delete($markerName)
$oversizedRaw = Join-Path $fixtureDist 'assets/oversized.bin'
$rawStream = [IO.File]::Create($oversizedRaw)
try { $rawStream.SetLength(64MB + 1) } finally { $rawStream.Dispose() }
Assert-Rejected 'oversized raw asset' 'Production asset exceeds the 64 MiB scan limit'
[IO.File]::Delete($oversizedRaw)
function Write-CompressedFixture([string]$Kind, [string]$Path, [string]$Text, [long]$ExpandedBytes = 0) {
    $stream = [IO.File]::Create($Path)
    if ($Kind -eq 'gz') { $compressor = [IO.Compression.GZipStream]::new($stream, [IO.Compression.CompressionLevel]::Fastest) }
    else { $compressor = [IO.Compression.BrotliStream]::new($stream, [IO.Compression.CompressionLevel]::Fastest) }
    try {
        if ($ExpandedBytes -gt 0) {
            $buffer = [byte[]]::new(65536)
            while ($ExpandedBytes -gt 0) { $count = [int][Math]::Min($ExpandedBytes, $buffer.Length); $compressor.Write($buffer, 0, $count); $ExpandedBytes -= $count }
        } else { $bytes = [Text.Encoding]::UTF8.GetBytes($Text); $compressor.Write($bytes, 0, $bytes.Length) }
    } finally { $compressor.Dispose(); $stream.Dispose() }
}
foreach ($kind in @('gz','br')) {
    $compressed = Join-Path $fixtureDist ('assets/compressed.js.' + $kind)
    Write-CompressedFixture $kind $compressed 'window.fixture = "synthetic-ui-review";'
    Assert-Rejected "$kind fixture identity" 'Fixture identity synthetic-ui-review is forbidden in production dist'
    [IO.File]::WriteAllBytes($compressed, [byte[]](0, 1, 2, 3))
    Assert-Rejected "$kind malformed asset" 'Malformed compressed production asset'
    Write-CompressedFixture $kind $compressed 'window.production = true;'
    $bytes = [IO.File]::ReadAllBytes($compressed)
    [IO.File]::WriteAllBytes($compressed, $bytes[0..($bytes.Length - 2)])
    Assert-Rejected "$kind truncated asset" 'Malformed compressed production asset'
    if ($kind -eq 'gz') {
        Write-CompressedFixture $kind $compressed 'window.production = true;'
        $bytes = [IO.File]::ReadAllBytes($compressed)
        [IO.File]::WriteAllBytes($compressed, [byte[]]($bytes + [Text.Encoding]::UTF8.GetBytes('synthetic-ui-review')))
        Assert-Rejected 'gzip trailing raw fixture identity' 'Fixture identity synthetic-ui-review is forbidden in production dist'
    }
    Write-CompressedFixture $kind $compressed '' (64MB + 1)
    Assert-Rejected "$kind oversized decoded asset" 'Production asset exceeds the 64 MiB scan limit'
    [IO.File]::Delete($compressed)
}
$originalService = [IO.File]::ReadAllBytes($fixtureService)
[IO.File]::Delete($fixtureService)
[IO.Directory]::Delete((Split-Path $fixtureService))
$redirectedTarget = Join-Path $fixture '.cache/redirected-target'
[IO.Directory]::CreateDirectory($redirectedTarget) | Out-Null
New-Item -ItemType Junction -Path (Split-Path $fixtureService) -Target $redirectedTarget | Out-Null
try { Assert-Rejected 'backend output junction before Maven' 'Symlinks and junctions are forbidden in release paths' @{ SkipBackendBuild = $false } }
finally { Remove-Item -LiteralPath (Split-Path $fixtureService); [IO.Directory]::CreateDirectory((Split-Path $fixtureService)) | Out-Null; [IO.File]::WriteAllBytes($fixtureService, $originalService) }
$indexBytes = [IO.File]::ReadAllBytes((Join-Path $fixtureDist 'index.html'))
$appBytes = [IO.File]::ReadAllBytes((Join-Path $fixtureDist 'assets/app.js'))
[IO.File]::Delete((Join-Path $fixtureDist 'index.html')); [IO.File]::Delete((Join-Path $fixtureDist 'assets/app.js'))
[IO.Directory]::Delete((Join-Path $fixtureDist 'assets')); [IO.Directory]::Delete($fixtureDist)
$redirectedDist = Join-Path $fixture '.cache/redirected-dist'
[IO.Directory]::CreateDirectory($redirectedDist) | Out-Null
New-Item -ItemType Junction -Path $fixtureDist -Target $redirectedDist | Out-Null
try { Assert-Rejected 'UI output junction before Node' 'Symlinks and junctions are forbidden in release paths' @{ SkipUiBuild = $false } }
finally { Remove-Item -LiteralPath $fixtureDist; [IO.Directory]::CreateDirectory((Join-Path $fixtureDist 'assets')) | Out-Null; [IO.File]::WriteAllBytes((Join-Path $fixtureDist 'index.html'), $indexBytes); [IO.File]::WriteAllBytes((Join-Path $fixtureDist 'assets/app.js'), $appBytes) }
[IO.File]::AppendAllText((Join-Path $fixture 'openmetadata-service/pom.xml'), 'dirty')
Assert-Rejected 'tracked production edit' 'Production source or release tooling has uncommitted changes'
[IO.File]::WriteAllText((Join-Path $fixture 'openmetadata-service/pom.xml'), '<project/>')
$untracked = Join-Path $fixture 'openmetadata-service/New.java'
[IO.File]::WriteAllText($untracked, 'class New {}')
Assert-Rejected 'untracked production edit' 'Production source or release tooling has uncommitted changes'
[IO.File]::Delete($untracked)
Write-Output "$passed input rejection checks and actual-template check passed; external build calls=$($mockState.unexpectedCalls); fixture=$fixture"
if ($CheckArchivePackaging) {
    $stream = [IO.File]::Open($fixtureService, [IO.FileMode]::Create, [IO.FileAccess]::Write)
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try { $null = $archive.CreateEntry('org/openmetadata/service/OpenMetadataApplication.class') } finally { $archive.Dispose(); $stream.Dispose() }
    $arguments.ExpectedServiceSha256 = Get-OracleCdcFileSha256 -Path $fixtureService
    $arguments.JarExecutable = $JarExecutable
    Set-Item -LiteralPath Function:script:docker -Value ({
        if ($args[0] -eq 'image' -and $args[1] -eq 'inspect') { return ('sha256:' + ('1' * 64)) }
        if ($args[0] -ne 'build' -or $args -notcontains '--pull=false' -or $args -notcontains '--network=none') { throw 'Unexpected Docker boundary arguments' }
        if ([Environment]::GetEnvironmentVariable('DOCKER_BUILDKIT', 'Process') -ne '0') { throw 'Registry metadata resolution was not disabled' }
        $context = $args[-1]
        $actual = @(Get-ChildItem -LiteralPath $context -File -Force | Select-Object -ExpandProperty Name | Sort-Object)
        if (Compare-Object $actual @('.dockerignore','openmetadata-service-2.0.4.jar','openmetadata-ui-2.0.4.jar')) { throw 'Docker context differs from allowlist' }
        $mockState.contexts.Add($context)
        throw 'OFFLINE_PACKAGING_REACHED_DOCKER_BOUNDARY'
    }.GetNewClosure())
    $previousBuildKit = [Environment]::GetEnvironmentVariable('DOCKER_BUILDKIT', 'Process')
    try {
        foreach ($state in @('absent','empty','1')) {
            if ($state -eq 'absent') { Remove-Item -LiteralPath Env:DOCKER_BUILDKIT -ErrorAction SilentlyContinue }
            else { [Environment]::SetEnvironmentVariable('DOCKER_BUILDKIT', $(if ($state -eq 'empty') { '' } else { $state }), 'Process') }
            $beforeValue = [Environment]::GetEnvironmentVariable('DOCKER_BUILDKIT', 'Process')
            $beforePresent = [Environment]::GetEnvironmentVariables('Process').Contains('DOCKER_BUILDKIT')
            $caught = $null
            try { & $fixtureBuilder @arguments | Out-Null } catch { $caught = $_.Exception.Message }
            if ($caught -ne 'OFFLINE_PACKAGING_REACHED_DOCKER_BOUNDARY') { throw "FAIL: archive fixture: $caught" }
            $afterValue = [Environment]::GetEnvironmentVariable('DOCKER_BUILDKIT', 'Process')
            $afterPresent = [Environment]::GetEnvironmentVariables('Process').Contains('DOCKER_BUILDKIT')
            if ($beforePresent -ne $afterPresent -or $beforeValue -cne $afterValue) { throw "FAIL: $state Docker builder environment was not exactly restored" }
            Write-Output "PASS: $state Docker builder environment exactly restored after failure"
        }
        $hashes = @($mockState.contexts | ForEach-Object { Get-OracleCdcFileSha256 -Path (Join-Path $_ 'openmetadata-ui-2.0.4.jar') } | Select-Object -Unique)
        if ($hashes.Count -ne 1) { throw 'FAIL: equivalent production dist/sourceDate produced different UI JARs' }
        Write-Output "PASS: three deterministic fixture archives, exact context allowlist; UI SHA256=$($hashes[0]); Docker process calls=0"
    } finally {
        if ($null -eq $previousBuildKit) { Remove-Item -LiteralPath Env:DOCKER_BUILDKIT -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable('DOCKER_BUILDKIT', $previousBuildKit, 'Process') }
    }
}
} finally {
    if ($hadLastExitCode) { Set-Variable -Name LASTEXITCODE -Scope Global -Value $savedLastExitCodeValue -Force }
    else { Remove-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue }
}
