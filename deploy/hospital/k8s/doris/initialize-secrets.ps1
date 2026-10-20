[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
$outputDirectory = Join-Path $repoRoot '.cache\doris\secrets'
$bootstrapPath = Join-Path $outputDirectory 'bootstrap.secret.json'
$writerPath = Join-Path $outputDirectory 'writer.secret.json'

if ((Test-Path -LiteralPath $bootstrapPath) -or (Test-Path -LiteralPath $writerPath)) {
    throw 'Doris Secret files already exist. Preserve and reuse them; this script never rotates or overwrites credentials.'
}

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$directoryAcl = New-Object Security.AccessControl.DirectorySecurity
$directoryAcl.SetOwner($identity)
$directoryAcl.SetAccessRuleProtection($true, $false)
$rule = New-Object Security.AccessControl.FileSystemAccessRule(
    $identity,
    [Security.AccessControl.FileSystemRights]::FullControl,
    [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
    [Security.AccessControl.PropagationFlags]::None,
    [Security.AccessControl.AccessControlType]::Allow
)
$directoryAcl.AddAccessRule($rule)
Set-Acl -LiteralPath $outputDirectory -AclObject $directoryAcl

function New-DorisPassword {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    }
    finally {
        $random.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Get-DorisPasswordHash([string] $Password) {
    $sha = [Security.Cryptography.SHA1]::Create()
    $plainBytes = [Text.Encoding]::UTF8.GetBytes($Password)
    try {
        $first = $sha.ComputeHash($plainBytes)
        $second = $sha.ComputeHash($first)
        return '*' + [BitConverter]::ToString($second).Replace('-', '')
    }
    finally {
        $sha.Dispose()
        [Array]::Clear($plainBytes, 0, $plainBytes.Length)
    }
}

function ConvertTo-SecretData([System.Collections.IDictionary] $Values) {
    $encoded = [ordered]@{}
    foreach ($key in $Values.Keys) {
        $encoded[$key] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Values[$key]))
    }
    return $encoded
}

function Write-DorisSecret([string] $Name, [string] $Path, [System.Collections.IDictionary] $Values) {
    $manifest = [ordered]@{
        apiVersion = 'v1'
        kind = 'Secret'
        metadata = [ordered]@{
            name = $Name
            namespace = 'datahub-open-test'
            labels = @{ 'app.kubernetes.io/part-of' = 'hospital-doris' }
        }
        type = 'Opaque'
        data = ConvertTo-SecretData $Values
    }
    $json = $manifest | ConvertTo-Json -Depth 10
    [IO.File]::WriteAllText($Path, $json + "`n", (New-Object Text.UTF8Encoding($false)))
}

$rootPassword = New-DorisPassword
$writerPassword = New-DorisPassword
try {
    $rootHash = Get-DorisPasswordHash $rootPassword
    $writerHash = Get-DorisPasswordHash $writerPassword
    $rootClient = "[client]`nuser=root`npassword=$rootPassword`nhost=127.0.0.1`nport=9030`nprotocol=tcp`n"
    $writerClient = "[client]`nuser=hospital_ods_writer`npassword=$writerPassword`nhost=127.0.0.1`nport=9030`nprotocol=tcp`n"
    Write-DorisSecret 'hospital-doris-bootstrap' $bootstrapPath ([ordered]@{
        username = 'root'
        password = $rootPassword
        'initial-root-password' = $rootHash
        'client.cnf' = $rootClient
    })
    Write-DorisSecret 'hospital-doris-writer' $writerPath ([ordered]@{
        username = 'hospital_ods_writer'
        password = $writerPassword
        'password-hash' = $writerHash
        'client.cnf' = $writerClient
    })
}
finally {
    $rootPassword = $null
    $writerPassword = $null
    $rootHash = $null
    $writerHash = $null
    $rootClient = $null
    $writerClient = $null
}

Write-Host "Created two Secret manifests in $outputDirectory with access restricted to the current Windows identity."
Write-Host 'No credentials were printed and no Kubernetes resources were created. Keep these files outside version control.'
