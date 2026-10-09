param(
    [string]$Namespace = 'datahub-open-test',
    [string]$Kubectl = 'kubectl'
)
$ErrorActionPreference = 'Stop'
if ($Namespace -notmatch '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$') {
    throw 'Invalid namespace'
}
$existing = & $Kubectl get secret hospital-openmetadata-runtime -n $Namespace `
    --ignore-not-found -o name --request-timeout=15s
if ($LASTEXITCODE -ne 0) { throw 'Cannot check runtime Secret' }
if ($existing) {
    Write-Output "$existing already exists; preserving its credentials."
    return
}
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $databaseBytes = New-Object byte[] 32
    $fernetBytes = New-Object byte[] 32
    $generator.GetBytes($databaseBytes)
    $generator.GetBytes($fernetBytes)
    $secret = @{
        apiVersion = 'v1'
        kind = 'Secret'
        metadata = @{
            name = 'hospital-openmetadata-runtime'
            namespace = $Namespace
            labels = @{'app.kubernetes.io/part-of' = 'hospital-metadata'}
        }
        type = 'Opaque'
        stringData = @{
            DB_USER_PASSWORD = [Convert]::ToBase64String($databaseBytes)
            FERNET_KEY = [Convert]::ToBase64String($fernetBytes).Replace('+', '-').Replace('/', '_')
        }
    }
    $secret | ConvertTo-Json -Depth 7 -Compress | & $Kubectl create -f - --request-timeout=15s
    if ($LASTEXITCODE -ne 0) { throw 'Runtime Secret creation failed' }
} finally {
    $generator.Dispose()
    Remove-Variable secret, databaseBytes, fernetBytes -ErrorAction SilentlyContinue
}
