param(
    [string]$Image = 'hospital-openmetadata:2.0.4-integrate-v6',
    [switch]$Push
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$context = Join-Path $repository '.cache/hospital-image'
$release = Join-Path $repository '.cache/openmetadata-2.0.4.tar.gz'
$serviceJar = Join-Path $repository 'openmetadata-service/target/openmetadata-service-2.0.4.jar'
$uiJar = Join-Path $repository 'openmetadata-ui/target/openmetadata-ui-2.0.4.jar'
foreach ($jar in @($serviceJar, $uiJar)) {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Build the final service and UI JAR before preparing the image: $jar"
    }
}
New-Item -ItemType Directory -Path $context -Force | Out-Null
if (-not (Test-Path -LiteralPath $release)) {
    & curl.exe --location --fail --silent --show-error --retry 2 --output $release `
        'https://github.com/open-metadata/OpenMetadata/releases/download/2.0.4-release/openmetadata-2.0.4.tar.gz'
    if ($LASTEXITCODE -ne 0) { throw 'Official distribution download failed' }
}
Copy-Item -LiteralPath $release -Destination (Join-Path $context 'openmetadata-2.0.4.tar.gz')
Copy-Item -LiteralPath $serviceJar -Destination (Join-Path $context 'openmetadata-service-2.0.4.jar')
Copy-Item -LiteralPath $uiJar -Destination (Join-Path $context 'openmetadata-ui-2.0.4.jar')
& (Join-Path $PSScriptRoot 'seatunnel/prepare-drivers.ps1') -Destination (Join-Path $context 'jdbc-drivers')
& docker build --file (Join-Path $PSScriptRoot 'Dockerfile') --tag $Image $context
if ($LASTEXITCODE -ne 0) { throw 'Hospital image build failed' }
if ($Push) {
    & docker push $Image
    if ($LASTEXITCODE -ne 0) { throw 'Hospital image push failed' }
}
