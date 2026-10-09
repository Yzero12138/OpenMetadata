param(
    [string]$Context = 'kubernetes-admin@kubernetes',
    [string]$Namespace = 'datahub-open-test',
    [string]$Kubectl = 'kubectl'
)
$ErrorActionPreference = 'Stop'
if ($Namespace -ne 'datahub-open-test') { throw 'This synthetic bootstrap is limited to datahub-open-test' }
function New-RandomPassword {
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
$definitions = @{
    'hospital-integration-runtime' = @('HOSPITAL_SOURCE_PASSWORD','HOSPITAL_TARGET_PASSWORD')
    'hospital-integration-bootstrap' = @('SOURCE_ADMIN_PASSWORD','TARGET_ADMIN_PASSWORD')
}
foreach ($name in $definitions.Keys) {
    $existingText = & $Kubectl --context $Context --request-timeout=10s -n $Namespace get secret $name --ignore-not-found -o json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot check existing synthetic credentials' }
    if ($existingText) {
        $existing = ($existingText -join "`n") | ConvertFrom-Json
        foreach ($key in $definitions[$name]) {
            if (-not $existing.data.$key) { throw "Existing Secret is missing a required key: $name/$key" }
        }
        Write-Output "Preserved existing Secret: $name"
        continue
    }
    $data = @{}
    foreach ($key in $definitions[$name]) {
        $password = New-RandomPassword
        $data[$key] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($password))
        $password = $null
    }
    $manifest = @{
        apiVersion='v1'; kind='Secret'; type='Opaque';
        metadata=@{name=$name;namespace=$Namespace;labels=@{'app.kubernetes.io/part-of'='hospital-integration'}};
        data=$data
    }
    $manifest | ConvertTo-Json -Depth 8 -Compress | & $Kubectl --context $Context --request-timeout=10s -n $Namespace create -f -
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic credential creation failed' }
    $data.Clear()
    $manifest = $null
    $existing = $null
}
