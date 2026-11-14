param(
    [string]$Image = 'hospital-seatunnel:3.0.0-oracle-cdc-v1',
    [string]$JavaHome = 'D:/Program Files/BellSoft/LibericaJDK-21'
)
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskClasses = Join-Path $taskRepository '.cache/oracle-cdc/client-packaging/loader-classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') --release 17 -encoding UTF-8 -proc:none -d $taskClasses (Join-Path $PSScriptRoot 'loader-smoke/OracleCdcLoaderSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Oracle CDC loader smoke compilation failed' }
$taskContainer = 'hospital-oracle-cdc-loader-' + [Guid]::NewGuid().ToString('N')
& docker run --rm --name $taskContainer --network none --read-only --hostname localhost --cap-drop ALL --security-opt no-new-privileges --mount ('type=bind,source=' + $taskClasses + ',target=/smoke,readonly') --entrypoint java $Image -XX:-UsePerfData -cp /smoke org.openmetadata.integration.seatunnel.OracleCdcLoaderSmoke /opt/seatunnel
$taskSmokeExit = $LASTEXITCODE
$taskRemaining = & docker ps --all --quiet --filter ('name=^/' + $taskContainer + '$')
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify smoke container removal' }
if ($taskRemaining) { throw 'Oracle CDC smoke container was not removed' }
if ($taskSmokeExit -ne 0) { throw 'Oracle CDC loader smoke failed' }
Write-Output 'Verified one-shot loader exited and its container was removed'
