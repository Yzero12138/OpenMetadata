param(
    [string]$Kubectl = 'C:\Program Files\Docker\Docker\resources\bin\kubectl.exe',
    [string]$Context = 'kubernetes-admin@kubernetes',
    [string]$Namespace = 'datahub-open-test'
)
$ErrorActionPreference = 'Stop'
if ($Namespace -ne 'datahub-open-test') { throw 'Only the approved isolated test namespace is supported' }
$probeName = 'hospital-integration-network-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$outputRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.logs'))
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$checks = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [bool]$Passed) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed })
    if (-not $Passed) { throw "Network verification failed: $Name" }
}
$probeCreated = $false
try {
    $service = & $Kubectl --context $Context --request-timeout=10s -n $Namespace get service hospital-seatunnel -o json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Engine service cannot be inspected' }
    Check 'engine-service-is-cluster-internal' ($service.spec.type -eq 'ClusterIP' -and -not $service.spec.externalIPs)
    $overview = & $Kubectl --context $Context --request-timeout=10s -n $Namespace exec deployment/hospital-openmetadata -c openmetadata -- curl --fail --silent --show-error --connect-timeout 3 --max-time 5 http://hospital-seatunnel:8080/overview
    if ($LASTEXITCODE -ne 0) { throw 'The authorized application cannot reach the engine' }
    Check 'application-can-reach-engine' (($overview -join '') | ConvertFrom-Json).projectVersion.Equals('3.0.0')
    & $Kubectl --context $Context --request-timeout=10s -n $Namespace exec deployment/hospital-openmetadata -c openmetadata -- timeout 5 bash -c 'exec 3<>/dev/tcp/hospital-integration-source/5432; exec 4<>/dev/tcp/hospital-integration-ods/5432'
    Check 'application-can-reach-dedicated-databases' ($LASTEXITCODE -eq 0)
    $application = & $Kubectl --context $Context --request-timeout=10s -n $Namespace get deployment hospital-openmetadata -o json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Application image cannot be inspected' }
    $image = @($application.spec.template.spec.containers | Where-Object name -eq openmetadata)[0].image
    # No runtime environment, credentials, service account token or allowed workload label.
    $command = @'
set -euo pipefail
getent hosts hospital-seatunnel >/dev/null
getent hosts hospital-integration-source >/dev/null
getent hosts hospital-integration-ods >/dev/null
if curl --fail --silent --connect-timeout 3 --max-time 5 http://hospital-seatunnel:8080/overview >/dev/null 2>&1; then
  echo 'FAIL engine REST reachable from an unrelated Pod'; exit 1
fi
echo 'PASS unrelated Pod cannot reach engine REST'
if timeout 5 bash -c 'exec 3<>/dev/tcp/hospital-integration-source/5432' 2>/dev/null; then
  echo 'FAIL source reachable from an unrelated Pod'; exit 1
fi
echo 'PASS unrelated Pod cannot reach source'
if timeout 5 bash -c 'exec 3<>/dev/tcp/hospital-integration-ods/5432' 2>/dev/null; then
  echo 'FAIL ODS reachable from an unrelated Pod'; exit 1
fi
echo 'PASS unrelated Pod cannot reach ODS'
'@
    $manifest = @{
        apiVersion = 'v1'; kind = 'Pod'
        metadata = @{ name = $probeName; namespace = $Namespace; labels = @{ 'app.kubernetes.io/name' = 'hospital-integration-negative-probe' } }
        spec = @{
            restartPolicy = 'Never'; activeDeadlineSeconds = 90; automountServiceAccountToken = $false
            imagePullSecrets = @(@{ name = 'harbor-cred' })
            securityContext = @{ runAsNonRoot = $true; runAsUser = 1000; runAsGroup = 1000 }
            containers = @(@{
                name = 'probe'; image = $image; command = @('/bin/bash', '-ec', $command)
                securityContext = @{ allowPrivilegeEscalation = $false; capabilities = @{ drop = @('ALL') } }
                resources = @{ requests = @{ cpu = '10m'; memory = '32Mi' }; limits = @{ cpu = '100m'; memory = '128Mi' } }
            })
        }
    }
    $manifest | ConvertTo-Json -Depth 12 | & $Kubectl --context $Context --request-timeout=10s create -f - | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create scoped verification Pod' }
    $probeCreated = $true
    $deadline = [DateTime]::UtcNow.AddSeconds(100)
    do {
        $pod = & $Kubectl --context $Context --request-timeout=10s -n $Namespace get pod $probeName -o json | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect scoped verification Pod' }
        if ($pod.status.phase -in @('Succeeded', 'Failed')) { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    $probeLogs = & $Kubectl --context $Context --request-timeout=10s -n $Namespace logs $probeName
    $probeLogs | Set-Content -LiteralPath (Join-Path $outputRoot 'integration-network-probe.log') -Encoding utf8
    Check 'unrelated-pod-denied-engine-and-both-databases' ($pod.status.phase -eq 'Succeeded' -and @($probeLogs | Where-Object { $_ -like 'PASS *' }).Count -eq 3)
    $checks | Format-Table -AutoSize
    @{ checkedAt = [DateTime]::UtcNow.ToString('o'); checks = $checks.ToArray(); passed = $true } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRoot 'integration-network-verification.json') -Encoding utf8
} finally {
    if ($probeCreated) {
        & $Kubectl --context $Context --request-timeout=10s -n $Namespace delete pod $probeName --ignore-not-found --wait=false | Out-Null
    }
}
