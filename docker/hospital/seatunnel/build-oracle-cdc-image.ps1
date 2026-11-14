param([string]$Image = 'hospital-seatunnel:3.0.0-oracle-cdc-v1')
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskContext = Join-Path $taskRepository '.cache/seatunnel-oracle-cdc-3.0.0'
& (Join-Path $PSScriptRoot 'prepare-oracle-cdc.ps1') -Destination $taskContext
[IO.File]::WriteAllText((Join-Path $taskContext '.dockerignore'), "*`n!connector-cdc-oracle-3.0.0.jar`n", [Text.UTF8Encoding]::new($false))
$taskRevision = & git -C $taskRepository rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the source revision' }
& docker build --pull=false --network=none --file (Join-Path $PSScriptRoot 'Dockerfile.oracle-cdc') --build-arg ('SOURCE_REVISION=' + $taskRevision) --tag $Image $taskContext
if ($LASTEXITCODE -ne 0) { throw 'Hospital Oracle CDC image build failed' }
