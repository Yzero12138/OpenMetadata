param(
    [string]$Image = 'harbor.qcrmyy.local/coop/hospital-seatunnel:3.0.0-pg-v1',
    [switch]$Push
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$context = Join-Path $repository '.cache/seatunnel-3.0.0'
New-Item -ItemType Directory -Force -Path $context | Out-Null
$artifacts = @(
    @{ Name = 'apache-seatunnel-3.0.0-bin.tar.gz'; Url = 'https://dlcdn.apache.org/seatunnel/3.0.0/apache-seatunnel-3.0.0-bin.tar.gz'; Sha512 = '2825b9ef0708dcbb3c2cab238b8ed39b41fce0eb4d01582c026c57cec8cef9b047875c38ea5a03b6483737213cd49f8c6130f59c2ad6b689669ccb10cd5356e6' },
    @{ Name = 'connector-jdbc-3.0.0.jar'; Url = 'https://repo.maven.apache.org/maven2/org/apache/seatunnel/connector-jdbc/3.0.0/connector-jdbc-3.0.0.jar'; Sha512 = '8073d002d21d3ee00bcdf2e1aa74e83413c1ab34023f7bec27ed84e8df0fab534a1abcef2c61869aaabcf20eb33a48ffdd5ad6076e8feaeb443817e1d101292f' },
    @{ Name = 'connector-cdc-postgres-3.0.0.jar'; Url = 'https://repo.maven.apache.org/maven2/org/apache/seatunnel/connector-cdc-postgres/3.0.0/connector-cdc-postgres-3.0.0.jar'; Sha512 = 'e0f34e9be2ee304eed25ba2f5cb5ac098b725918655f7a3605220c9eb67333a1c7eb791ffcf720615c9c92cfb8e7fd6e56bb0889b5194d08b3dc418b789b2dfc' },
    @{ Name = 'postgresql-42.7.14.jar'; Url = 'https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar'; Sha512 = '2a7c94c3f86f7ad6cfa16a964fe79dec8b122449679c8bbdb18f7052593349c2d11dd07992034de88d6effd840e2edf5ea8542e947de19905c75677d0dd44abc' }
)
foreach ($artifact in $artifacts) {
    $path = Join-Path $context $artifact.Name
    if (-not (Test-Path -LiteralPath $path)) {
        & curl.exe --fail --location --silent --show-error --retry 2 --proto '=https' --output $path $artifact.Url
        if ($LASTEXITCODE -ne 0) { throw "Official artifact download failed: $($artifact.Name)" }
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA512).Hash.ToLowerInvariant() -ne $artifact.Sha512) {
        throw "SHA512 mismatch: $($artifact.Name)"
    }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'hospital-entrypoint.sh') -Destination $context
@('apache-seatunnel-3.0.0-bin.tar.gz','connector-jdbc-3.0.0.jar','connector-cdc-postgres-3.0.0.jar','postgresql-42.7.14.jar','hospital-entrypoint.sh') |
    ForEach-Object { "!$_" } | Set-Content -LiteralPath (Join-Path $context '.dockerignore') -Encoding utf8NoBOM
$ignorePath = Join-Path $context '.dockerignore'
[IO.File]::WriteAllText($ignorePath, "*`n" + (Get-Content -LiteralPath $ignorePath -Raw), [Text.UTF8Encoding]::new($false))
& docker build --file (Join-Path $PSScriptRoot 'Dockerfile') --tag $Image $context
if ($LASTEXITCODE -ne 0) { throw 'SeaTunnel image build failed' }
if ($Push) {
    & docker push $Image
    if ($LASTEXITCODE -ne 0) { throw 'SeaTunnel image push failed' }
}
