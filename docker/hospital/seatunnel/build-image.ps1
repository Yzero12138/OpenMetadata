param(
    [string]$Image = 'hospital-seatunnel:3.0.0-jdbc-v2',
    [switch]$Push
)
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskContext = Join-Path $taskRepository '.cache/seatunnel-3.0.0'
New-Item -ItemType Directory -Path $taskContext -Force | Out-Null
$taskAssets = @(
    @{ File = 'apache-seatunnel-3.0.0-bin.tar.gz'; Algorithm = 'SHA512'; Hash = '2825b9ef0708dcbb3c2cab238b8ed39b41fce0eb4d01582c026c57cec8cef9b047875c38ea5a03b6483737213cd49f8c6130f59c2ad6b689669ccb10cd5356e6'; Url = 'https://archive.apache.org/dist/seatunnel/3.0.0/apache-seatunnel-3.0.0-bin.tar.gz' },
    @{ File = 'connector-jdbc-3.0.0.jar'; Algorithm = 'SHA256'; Hash = 'e60700f09f0ffbdf18ec9744674c87d60a4a5180ad4b5288ae244917771f0f2d'; Url = 'https://repo.maven.apache.org/maven2/org/apache/seatunnel/connector-jdbc/3.0.0/connector-jdbc-3.0.0.jar' },
    @{ File = 'connector-cdc-postgres-3.0.0.jar'; Algorithm = 'SHA256'; Hash = '65104b887447ac0522a0b1017e419af0ead6b089fa53d26507563565b9b4ad6b'; Url = 'https://repo.maven.apache.org/maven2/org/apache/seatunnel/connector-cdc-postgres/3.0.0/connector-cdc-postgres-3.0.0.jar' },
    @{ File = 'postgresql-42.7.14.jar'; Algorithm = 'SHA256'; Hash = '73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c'; Url = 'https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar' }
)
foreach ($taskAsset in $taskAssets) {
    $taskFile = Join-Path $taskContext $taskAsset.File
    if (-not (Test-Path -LiteralPath $taskFile)) {
        & curl.exe --location --fail --silent --show-error --retry 2 --connect-timeout 15 --max-time 600 --output $taskFile $taskAsset.Url
        if ($LASTEXITCODE -ne 0) { throw 'Pinned SeaTunnel artifact download failed' }
    }
    if ((Get-FileHash -LiteralPath $taskFile -Algorithm $taskAsset.Algorithm).Hash.ToLowerInvariant() -ne $taskAsset.Hash) {
        throw ('Pinned SeaTunnel artifact checksum mismatch: ' + $taskAsset.File)
    }
}
& (Join-Path $PSScriptRoot 'prepare-drivers.ps1') -Destination (Join-Path $taskContext 'jdbc-drivers')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'hospital-entrypoint.sh') -Destination (Join-Path $taskContext 'hospital-entrypoint.sh')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'jdbc-compat') -Destination $taskContext -Recurse -Force
$taskIgnore = @"
*
!apache-seatunnel-3.0.0-bin.tar.gz
!connector-jdbc-3.0.0.jar
!connector-cdc-postgres-3.0.0.jar
!postgresql-42.7.14.jar
!hospital-entrypoint.sh
!jdbc-drivers/
!jdbc-drivers/*.jar
!jdbc-compat/
!jdbc-compat/*.java
"@
[IO.File]::WriteAllText((Join-Path $taskContext '.dockerignore'), $taskIgnore + "`n", [Text.UTF8Encoding]::new($false))
& docker build --file (Join-Path $PSScriptRoot 'Dockerfile') --tag $Image $taskContext
if ($LASTEXITCODE -ne 0) { throw 'Hospital integration engine image build failed' }
if ($Push) {
    & docker push $Image
    if ($LASTEXITCODE -ne 0) { throw 'Hospital integration engine image push failed' }
}
