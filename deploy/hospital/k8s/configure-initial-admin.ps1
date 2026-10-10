param(
    [Parameter(Mandatory = $true)]
    [string]$Subject,
    [string]$Namespace = 'datahub-open-test',
    [string]$Kubectl = 'kubectl'
)
$ErrorActionPreference = 'Stop'
if ($Namespace -notmatch '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$') {
    throw 'Invalid namespace'
}
if ([string]::IsNullOrWhiteSpace($Subject) -or $Subject -match '[\r\n]') {
    throw 'Use the exact stable subject verified in Integrate, not an employee number.'
}
$configJson = & $Kubectl get configmap hospital-openmetadata-config -n $Namespace `
    -o json --request-timeout=15s
if ($LASTEXITCODE -ne 0) { throw 'Cannot read hospital identity configuration' }
$config = $configJson | ConvertFrom-Json
$issuer = $config.data.INTEGRATE_SSO_ISSUER
if ($config.data.INTEGRATE_SSO_ENABLED -ne 'true' -or [string]::IsNullOrWhiteSpace($issuer)) {
    throw 'Integrate SSO must be configured before appointing its initial administrator.'
}
$existing = & $Kubectl get secret hospital-openmetadata-runtime -n $Namespace `
    -o name --request-timeout=15s
if ($LASTEXITCODE -ne 0 -or -not $existing) { throw 'Provision the runtime Secret first' }
$hasher = [Security.Cryptography.SHA256]::Create()
try {
    $bytes = [Text.Encoding]::UTF8.GetBytes($issuer + "`n" + $Subject)
    $digest = $hasher.ComputeHash($bytes)
    $principal = 'integrate_' + [BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()
    $principals = ConvertTo-Json -InputObject @($principal) -Compress
    $patch = @{stringData = @{AUTHORIZER_ADMIN_PRINCIPALS = $principals}}
    $patchJson = $patch | ConvertTo-Json -Depth 4 -Compress
    & $Kubectl patch secret hospital-openmetadata-runtime -n $Namespace `
        --type=merge --patch $patchJson --request-timeout=15s
    if ($LASTEXITCODE -ne 0) { throw 'Cannot configure the initial administrator' }
    Write-Output 'Initial administrator configured; roll out the hospital Deployment to initialize the role.'
} finally {
    $hasher.Dispose()
}
