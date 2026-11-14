param(
    [string]$Image = 'hospital-seatunnel:3.0.0-cdc-v2',
    [string]$JavaHome = 'D:/Program Files/BellSoft/LibericaJDK-21'
)
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskClasses = Join-Path $taskRepository '.cache/sqlserver-cdc-20261114/engine/loader-classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -proc:none -d $taskClasses (Join-Path $PSScriptRoot 'loader-smoke/SqlServerCdcLoaderSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'SQL Server CDC loader smoke compilation failed' }
$taskContainer = 'hospital-sqlserver-cdc-loader-' + [Guid]::NewGuid().ToString('N')
& docker run --rm --name $taskContainer --network none --read-only --hostname localhost --cap-drop ALL --security-opt no-new-privileges --mount ('type=bind,source=' + $taskClasses + ',target=/smoke,readonly') --entrypoint java $Image -XX:-UsePerfData -cp /smoke org.openmetadata.integration.seatunnel.SqlServerCdcLoaderSmoke /opt/seatunnel
$taskSmokeExit = $LASTEXITCODE
$taskRemaining = & docker ps --all --quiet --filter ('name=^/' + $taskContainer + '$')
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify smoke container removal' }
if ($taskRemaining) { throw 'SQL Server CDC smoke container was not removed' }
if ($taskSmokeExit -ne 0) { throw 'SQL Server CDC loader smoke failed' }
Write-Output 'Verified one-shot SQL Server loader exited and its container was removed'
