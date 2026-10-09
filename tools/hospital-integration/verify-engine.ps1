param(
    [string]$Kubectl = 'kubectl',
    [string]$Context = 'kubernetes-admin@kubernetes',
    [string]$Namespace = 'datahub-open-test',
    [string]$EngineUrl = 'http://127.0.0.1:19480',
    [ValidateSet('Full','All')][string]$Phase = 'All',
    [switch]$VerifyRestart,
    [switch]$ManagePortForward,
    [switch]$VerifyFieldMapping
)
$ErrorActionPreference = 'Stop'
if ($Namespace -ne 'datahub-open-test' -or $EngineUrl -notmatch '^http://127\.0\.0\.1:\d+$') {
    throw 'Verification is restricted to the isolated namespace and a loopback engine port-forward'
}
if ($VerifyRestart -and -not $ManagePortForward) { throw 'Restart verification requires -ManagePortForward' }
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidenceFile = if($VerifyFieldMapping){'integration-engine-mapping-verification.json'}else{'integration-engine-verification.json'}
$evidencePath = Join-Path $repository ".logs/$evidenceFile"
$failurePath = Join-Path $repository '.logs/integration-engine-failure.txt'
$evidence = [ordered]@{scope='isolated-synthetic-data';startedAt=[DateTimeOffset]::UtcNow.ToString('o');checks=@()}
$secret = $null
$sourcePassword = $null
$targetPassword = $null
$forwardProcess = $null
function Start-EngineForward {
    if ($script:forwardProcess -and -not $script:forwardProcess.HasExited) {
        Stop-Process -Id $script:forwardProcess.Id -Force
        $script:forwardProcess.WaitForExit()
    }
    if (-not $script:forwardProcess) {
        $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
        $listener.Start()
        $port=$listener.LocalEndpoint.Port
        $listener.Stop()
        $script:EngineUrl="http://127.0.0.1:$port"
    }
    $port=([Uri]$script:EngineUrl).Port
    $script:forwardProcess=Start-Process -FilePath $Kubectl -ArgumentList @('--context',$Context,'-n',$Namespace,'port-forward','--address=127.0.0.1','svc/hospital-seatunnel',"${port}:8080") -WindowStyle Hidden -RedirectStandardOutput (Join-Path $repository '.logs/integration-verifier-forward.log') -RedirectStandardError (Join-Path $repository '.logs/integration-verifier-forward-error.log') -PassThru
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        try { $null=Invoke-Engine GET '/overview'; return } catch {}
        if($script:forwardProcess.HasExited){throw 'Loopback verification forwarding process exited'}
        Start-Sleep -Milliseconds 500
    }while([DateTimeOffset]::UtcNow -lt $deadline)
    throw 'Loopback verification forwarding did not become ready'
}
function Add-Check([string]$Name, $Value) {
    $script:evidence.checks += @{name=$Name;value=$Value}
    Write-Output "PASS $Name"
    $script:evidence | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM
}
function Invoke-Engine([string]$Method, [string]$Path, $Body = $null) {
    if ($Path -eq '/submit-job' -and $Body.params) {
        $parameters = $Body.params
        $query = @($parameters.Keys | Sort-Object | ForEach-Object {
            [Uri]::EscapeDataString($_) + '=' + [Uri]::EscapeDataString([string]$parameters[$_])
        }) -join '&'
        $Path = '/submit-job?' + $query
        $Body = $Body.Clone()
        $Body.Remove('params')
    }
    $arguments = @{Uri="$EngineUrl$Path";Method=$Method;TimeoutSec=90;ErrorAction='Stop'}
    if ($null -ne $Body) {
        $arguments.ContentType='application/json'
        $arguments.Body=($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    try { Invoke-RestMethod @arguments }
    catch {
        $safe = [string]$_.ErrorDetails.Message
        foreach ($password in @($script:sourcePassword,$script:targetPassword)) {
            if ($password) { $safe=$safe.Replace($password,'[REDACTED]') }
        }
        $safe=$safe -replace 'jdbc:postgresql:[^\s"<>]*','[JDBC REDACTED]'
        [IO.File]::WriteAllText($failurePath,$safe,[Text.UTF8Encoding]::new($false))
        throw "Engine request failed: $Method $Path; sanitized details in .logs/integration-engine-failure.txt"
    }
}
function Invoke-SyntheticSql([ValidateSet('source','ods')][string]$Database,[string]$Sql) {
    $pod="hospital-integration-$Database-0"
    $databaseName=if($Database -eq 'source'){'synthetic_source'}else{'synthetic_ods'}
    $result=$Sql | & $Kubectl --context $Context --request-timeout=10s -n $Namespace exec -i $pod -- psql -X -qAt --set=ON_ERROR_STOP=1 -U synthetic_admin -d $databaseName
    if ($LASTEXITCODE -ne 0) { throw "Synthetic SQL failed in $Database" }
    ($result -join "`n").Trim()
}
function Wait-Job([string]$JobId,[string[]]$Expected,[int]$Seconds=240) {
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds($Seconds)
    do {
        $job=Invoke-Engine GET "/job-info/$JobId"
        if ($job.jobStatus -in $Expected) { return $job }
        if ($job.jobStatus -in @('FAILED','CANCELED') -and $job.jobStatus -notin $Expected) {
            $safe=[string]$job.errorMsg
            foreach($password in @($script:sourcePassword,$script:targetPassword)) {
                if($password){$safe=$safe.Replace($password,'[REDACTED]')}
            }
            [IO.File]::WriteAllText($failurePath,$safe,[Text.UTF8Encoding]::new($false))
            throw "Job $JobId entered $($job.jobStatus); sanitized details in .logs/integration-engine-failure.txt"
        }
        Start-Sleep -Seconds 2
    } while([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Job $JobId did not reach $($Expected -join '/') before the deadline; last state $($job.jobStatus)"
}
function Get-TableDigest([string]$Database,[string]$Table) {
    if($Table -eq 'visit_events_mapped'){
        return Invoke-SyntheticSql ods "SELECT count(*) || ':' || md5(COALESCE(string_agg(event_id::text || '|' || department || '|' || event_type || '|' || to_char(modified_at, 'YYYY-MM-DD HH24:MI:SS.US'), E'\n' ORDER BY event_id),'')) FROM public.visit_events_mapped;"
    }
    Invoke-SyntheticSql $Database "SELECT count(*) || ':' || md5(COALESCE(string_agg(visit_id::text || '|' || department_code || '|' || visit_type || '|' || to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS.US'), E'\n' ORDER BY visit_id),'')) FROM public.$Table;"
}
function Wait-Convergence([string]$Table,[int]$ExpectedCount,[string]$JobId) {
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(180)
    do {
        $source=Get-TableDigest source visit_events
        $target=Get-TableDigest ods $Table
        if($source -eq $target -and $source.StartsWith("${ExpectedCount}:")){return $source}
        $job=Invoke-Engine GET "/job-info/$JobId"
        if($job.jobStatus -in @('FAILED','CANCELED')){throw "CDC convergence failed; engine state $($job.jobStatus)"}
        Start-Sleep -Seconds 2
    }while([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Synthetic $Table did not converge to $ExpectedCount exact rows"
}
function New-JobConfig([string]$JobId,[string]$Mode,[bool]$Resume=$false) {
    $sinkTable=if($Mode -eq 'BATCH'){'visit_events_full'}else{'visit_events_cdc'}
    $source=@{
        plugin_name='Jdbc';plugin_output='source';url='jdbc:postgresql://hospital-integration-source:5432/synthetic_source';
        driver='org.postgresql.Driver';username='hospital_source';password=$script:sourcePassword;
        query='SELECT visit_id, department_code, visit_type, updated_at FROM public.visit_events'
    }
    if($Mode -eq 'STREAMING'){
        $source=@{
            plugin_name='Postgres-CDC';plugin_output='source';url='jdbc:postgresql://hospital-integration-source:5432/synthetic_source';
            username='hospital_source';password=$script:sourcePassword;
            'database-names'=@('synthetic_source');'schema-names'=@('public');
            'table-names'=@('synthetic_source.public.visit_events');
            'decoding.plugin.name'='pgoutput';'startup.mode'='initial';'slot.name'='hospital_engine_verification';
            debezium=@{'publication.name'='hospital_engine_verification';'publication.autocreate.mode'='filtered';'slot.drop.on.stop'='false'}
        }
    }
    @{
        params=@{jobId=$JobId;jobName="hospital_synthetic_$Mode";isStartWithSavePoint=$Resume.ToString().ToLowerInvariant()};
        env=@{'job.mode'=$Mode;parallelism=1;'checkpoint.interval'=5000;'checkpoint.timeout'=60000;'checkpoint.retain-after-job-cancelled'=$true};
        source=@($source);transform=@();
        sink=@(@{
            plugin_name='Jdbc';plugin_input='source';url='jdbc:postgresql://hospital-integration-ods:5432/synthetic_ods';
            driver='org.postgresql.Driver';username='hospital_ods';password=$script:targetPassword;
            generate_sink_sql=$true;database='synthetic_ods';table="public.$sinkTable";primary_keys=@('visit_id');
            enable_upsert=$true;is_exactly_once=$false;batch_size=20;batch_interval_ms=1000;
            schema_save_mode='IGNORE';data_save_mode='APPEND_DATA'
        })
    }
}
try {
    if($ManagePortForward){Start-EngineForward}
    $overview=Invoke-Engine GET '/overview'
    if($overview.projectVersion -ne '3.0.0'){throw 'Unexpected engine version'}
    if([int]$overview.runningJobs -ne 0 -or [int]$overview.pendingJobs -ne 0){throw 'Refusing fixture reset while another engine task is active'}
    Add-Check 'fixed-engine-version' @{version=$overview.projectVersion;commit=$overview.gitCommitAbbrev;workers=$overview.workers}
    $secretText=& $Kubectl --context $Context --request-timeout=10s -n $Namespace get secret hospital-integration-runtime -o json
    if($LASTEXITCODE -ne 0){throw 'Cannot load synthetic runtime credential references'}
    $secret=($secretText -join "`n") | ConvertFrom-Json
    $secretText=$null
    $sourcePassword=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($secret.data.HOSPITAL_SOURCE_PASSWORD))
    $targetPassword=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($secret.data.HOSPITAL_TARGET_PASSWORD))
    $null=Invoke-SyntheticSql source "TRUNCATE public.visit_events; INSERT INTO public.visit_events SELECT n, 'DEPT_' || (n % 5), 'SYNTHETIC', TIMESTAMP '2026-10-09 00:00:00' FROM generate_series(1,100) n; ALTER TABLE public.visit_events REPLICA IDENTITY FULL;"
    $null=Invoke-SyntheticSql ods 'TRUNCATE public.visit_events_full, public.visit_events_cdc;'
    $fullId=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()+'000001'
    $fullConfig=New-JobConfig $fullId BATCH
    $submission=Invoke-Engine POST '/submit-job' $fullConfig
    if([string]$submission.jobId -ne $fullId){throw 'Engine changed the submitted stable full job ID'}
    $fullJob=Wait-Job $fullId @('FINISHED')
    $fullDigest=Wait-Convergence visit_events_full 100 $fullId
    Add-Check 'full-copy-exact-100' @{jobId=$fullId;state=$fullJob.jobStatus;rowDigest=$fullDigest}
    if($Phase -eq 'Full'){return}
    $cdcId=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()+'000002'
    $cdcConfig=New-JobConfig $cdcId STREAMING
    $submission=Invoke-Engine POST '/submit-job' $cdcConfig
    if([string]$submission.jobId -ne $cdcId){throw 'Engine changed the submitted stable CDC job ID'}
    $cdcJob=Wait-Job $cdcId @('RUNNING')
    $snapshotDigest=Wait-Convergence visit_events_cdc 100 $cdcId
    Add-Check 'cdc-snapshot-exact-100' @{jobId=$cdcId;rowDigest=$snapshotDigest}
    $null=Invoke-SyntheticSql source "INSERT INTO public.visit_events SELECT n, 'DEPT_' || (n % 5), 'SYNTHETIC', TIMESTAMP '2026-10-09 01:00:00' FROM generate_series(101,120) n; UPDATE public.visit_events SET department_code='DEPT_UPDATED',updated_at=TIMESTAMP '2026-10-09 02:00:00' WHERE visit_id BETWEEN 11 AND 20; DELETE FROM public.visit_events WHERE visit_id BETWEEN 1 AND 5;"
    $cdcDigest=Wait-Convergence visit_events_cdc 115 $cdcId
    Add-Check 'cdc-insert-update-delete-exact-115' @{rowDigest=$cdcDigest;inserted=20;updated=10;deleted=5}
    $null=Invoke-Engine POST '/stop-job' @{jobId=$cdcId;isStopWithSavePoint=$true}
    $stopped=Wait-Job $cdcId @('CANCELED','FINISHED','STOPPED','SAVEPOINT_DONE')
    $checkpoints=Invoke-Engine GET "/jobs/checkpoints/$cdcId"
    $savepoints=@($checkpoints.pipelines | Where-Object {$_.latestSavepoint.status -eq 'COMPLETED'})
    if($savepoints.Count -eq 0){throw 'Stop returned without a completed savepoint in the actual checkpoint API'}
    Add-Check 'cdc-savepoint-confirmed' @{jobId=$cdcId;state=$stopped.jobStatus;pipelines=$savepoints.Count}
    $null=Invoke-SyntheticSql source "UPDATE public.visit_events SET visit_type='RESUMED', updated_at=TIMESTAMP '2026-10-09 03:00:00' WHERE visit_id=21;"
    $resumeConfig=New-JobConfig $cdcId STREAMING $true
    $null=Invoke-Engine POST '/submit-job' $resumeConfig
    $null=Wait-Job $cdcId @('RUNNING')
    $resumeDigest=Wait-Convergence visit_events_cdc 115 $cdcId
    Add-Check 'cdc-savepoint-resume-exact-115' @{jobId=$cdcId;rowDigest=$resumeDigest}
    if($VerifyRestart){
        $oldUid=& $Kubectl --context $Context --request-timeout=10s -n $Namespace get pod hospital-seatunnel-0 -o 'jsonpath={.metadata.uid}'
        if($LASTEXITCODE -ne 0){throw 'Cannot read the original synthetic engine Pod identity'}
        $null=& $Kubectl --context $Context --request-timeout=10s -n $Namespace rollout restart statefulset hospital-seatunnel
        if($LASTEXITCODE -ne 0){throw 'Synthetic engine restart failed'}
        Write-Output 'Restart requested; waiting for a different ready engine Pod.'
        $deadline=[DateTimeOffset]::UtcNow.AddMinutes(4)
        do {
            $podText=& $Kubectl --context $Context --request-timeout=10s -n $Namespace get pod hospital-seatunnel-0 --ignore-not-found -o json
            if($LASTEXITCODE -ne 0){throw 'Cannot observe synthetic engine replacement'}
            $pod=if($podText){($podText -join "`n")|ConvertFrom-Json}else{$null}
            if($pod -and $pod.metadata.uid -ne $oldUid -and @($pod.status.conditions|Where-Object{$_.type -eq 'Ready' -and $_.status -eq 'True'}).Count -eq 1){break}
            Start-Sleep -Seconds 3
        }while([DateTimeOffset]::UtcNow -lt $deadline)
        if(-not $pod -or $pod.metadata.uid -eq $oldUid -or @($pod.status.conditions|Where-Object{$_.type -eq 'Ready' -and $_.status -eq 'True'}).Count -ne 1){throw 'A different ready engine Pod was not observed'}
        Start-EngineForward
        $job=Wait-Job $cdcId @('RUNNING','SAVEPOINT_DONE')
        $restartResume='automatic-running'
        if($job.jobStatus -eq 'SAVEPOINT_DONE'){
            $persisted=Invoke-Engine GET "/jobs/checkpoints/$cdcId"
            if(@($persisted.pipelines).Count -eq 0 -or @($persisted.pipelines|Where-Object{$_.latestSavepoint.status -ne 'COMPLETED'}).Count -ne 0){throw 'Restarted engine has no complete persisted savepoint for every pipeline'}
            $null=Invoke-Engine POST '/submit-job' (New-JobConfig $cdcId STREAMING $true)
            $job=Wait-Job $cdcId @('RUNNING')
            $restartResume='explicit-persisted-savepoint'
        }
        $null=Invoke-SyntheticSql source "UPDATE public.visit_events SET visit_type='RESTARTED',updated_at=TIMESTAMP '2026-10-09 04:00:00' WHERE visit_id=22;"
        $restartDigest=Wait-Convergence visit_events_cdc 115 $cdcId
        $historicalFull=Invoke-Engine GET "/job-info/$fullId"
        if($historicalFull.jobStatus -ne 'FINISHED'){throw 'Full job history was not persisted across engine restart'}
        Add-Check 'engine-restart-recovered' @{jobId=$cdcId;rowDigest=$restartDigest;podReplaced=$true;fullHistoryRetained=$true;newChangeApplied=$true;resumeMode=$restartResume}
    }
    if($VerifyFieldMapping){
        $null=Invoke-SyntheticSql ods 'TRUNCATE public.visit_events_mapped;'
        $mappedId=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()+'000003'
        $mappedConfig=New-JobConfig $mappedId STREAMING
        $mappedConfig.params.jobName='hospital_synthetic_field_mapping'
        $mappedConfig.source[0]['slot.name']='hospital_engine_mapping'
        $mappedConfig.source[0].debezium['publication.name']='hospital_engine_mapping'
        $mappedConfig.transform=@(@{plugin_name='Sql';plugin_input='source';plugin_output='mapped';query='SELECT visit_id AS event_id, department_code AS department, visit_type AS event_type, updated_at AS modified_at FROM source'})
        $mappedConfig.sink[0].plugin_input='mapped'
        $mappedConfig.sink[0].table='public.visit_events_mapped'
        $mappedConfig.sink[0].primary_keys=@('event_id')
        $null=Invoke-Engine POST '/submit-job' $mappedConfig
        $null=Wait-Job $mappedId @('RUNNING')
        $mappedSnapshot=Wait-Convergence visit_events_mapped 115 $mappedId
        Add-Check 'cdc-renamed-fields-snapshot-exact-115' @{jobId=$mappedId;rowDigest=$mappedSnapshot;fieldsRenamed=4}
        $null=Invoke-SyntheticSql source "INSERT INTO public.visit_events VALUES (121,'DEPT_MAPPING','MAPPED',TIMESTAMP '2026-10-09 05:00:00'); UPDATE public.visit_events SET visit_type='MAPPED',updated_at=TIMESTAMP '2026-10-09 05:00:00' WHERE visit_id=23; DELETE FROM public.visit_events WHERE visit_id=25;"
        $mappedDigest=Wait-Convergence visit_events_mapped 115 $mappedId
        $originalDigest=Wait-Convergence visit_events_cdc 115 $cdcId
        if($mappedDigest -ne $originalDigest){throw 'Mapped and unmapped CDC targets diverged'}
        Add-Check 'cdc-renamed-fields-insert-update-delete' @{jobId=$mappedId;rowDigest=$mappedDigest;renamedPrimaryKey=$true}
        $null=Invoke-Engine POST '/stop-job' @{jobId=$mappedId;isStopWithSavePoint=$true}
        $null=Wait-Job $mappedId @('SAVEPOINT_DONE')
        $mappedConfig=$null
    }
    $null=Invoke-Engine POST '/stop-job' @{jobId=$cdcId;isStopWithSavePoint=$true}
    $final=Wait-Job $cdcId @('CANCELED','FINISHED','STOPPED','SAVEPOINT_DONE')
    Add-Check 'verification-job-safely-stopped' @{jobId=$cdcId;state=$final.jobStatus}
    $evidence.completedAt=[DateTimeOffset]::UtcNow.ToString('o')
    $evidence.passed=$true
    $evidence | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM
}
finally {
    if($forwardProcess -and -not $forwardProcess.HasExited){Stop-Process -Id $forwardProcess.Id -Force}
    $sourcePassword=$null
    $targetPassword=$null
    $secret=$null
    $fullConfig=$null
    $cdcConfig=$null
    $resumeConfig=$null
    $mappedConfig=$null
}
