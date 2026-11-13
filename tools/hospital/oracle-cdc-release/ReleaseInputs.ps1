function Assert-OracleCdcRepositoryPath {
    param([string]$Repository, [string]$Path, [switch]$AllowMissing)
    $root = [IO.Path]::GetFullPath($Repository).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -ne $root -and -not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Release paths must remain inside the repository'
    }
    if (-not $AllowMissing -and -not (Test-Path -LiteralPath $full)) { throw "Required release input is missing: $full" }
    $current = $full
    while ($current.Length -ge $root.Length) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Symlinks and junctions are forbidden in release paths: $current"
            }
        }
        if ($current -eq $root) { break }
        $current = [IO.Path]::GetDirectoryName($current)
    }
    return $full
}

function Get-OracleCdcFileSha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-OracleCdcNoFixtureIdentity {
    param([IO.Stream]$Stream)
    $start = $Stream.Position
    try {
        $rawBuffer = [byte[]]::new(65536)
        $rawTail = ''
        $scanned = 0L
        while (($read = $Stream.Read($rawBuffer, 0, $rawBuffer.Length)) -gt 0) {
            $scanned += $read
            if ($scanned -gt 64MB) { throw 'Production asset exceeds the 64 MiB scan limit' }
            $rawText = $rawTail + [Text.Encoding]::ASCII.GetString($rawBuffer, 0, $read)
            if ($rawText.IndexOf('synthetic-ui-review', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw 'Fixture identity synthetic-ui-review is forbidden in production dist'
            }
            $rawTail = $rawText.Substring([Math]::Max(0, $rawText.Length - 18))
        }
    } finally { $Stream.Position = $start }
    $reader = [IO.StreamReader]::new($Stream, [Text.Encoding]::UTF8, $true, 65536, $true)
    try {
        $buffer = [char[]]::new(65536)
        $tail = ''
        while (($read = $reader.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $text = $tail + [string]::new($buffer, 0, $read)
            if ($text.IndexOf('synthetic-ui-review', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw 'Fixture identity synthetic-ui-review is forbidden in production dist'
            }
            $tail = $text.Substring([Math]::Max(0, $text.Length - 18))
        }
    } finally { $reader.Dispose() }
}

function Assert-OracleCdcAssetContents {
    param([string]$Path, [string]$Name)
    $limit = 64MB
    if ((Get-Item -LiteralPath $Path).Length -gt $limit) { throw 'Production asset exceeds the 64 MiB scan limit' }
    $encoded = [IO.File]::ReadAllBytes($Path)
    $inputStream = [IO.MemoryStream]::new($encoded, $false)
    try {
        Assert-OracleCdcNoFixtureIdentity -Stream $inputStream
        $inputStream.Position = 0
        if ($Name.EndsWith('.gz', [StringComparison]::OrdinalIgnoreCase)) {
            if ($encoded.Length -lt 20 -or $encoded[0] -ne 31 -or $encoded[1] -ne 139 -or $encoded[2] -ne 8 -or $encoded[3] -ne 0) {
                throw "Malformed compressed production asset: $Name"
            }
            # Vite writes one member with no optional header fields; the trailer catches silent truncated-stream EOF.
            $expectedSize = [BitConverter]::ToUInt32($encoded, $encoded.Length - 4)
            if ($expectedSize -gt $limit) { throw 'Production asset exceeds the 64 MiB scan limit' }
            if (-not $script:OracleCdcStrictCompressionValidated) {
                $probe = [IO.MemoryStream]::new([byte[]](3))
                $probeDecoder = [IO.Compression.DeflateStream]::new($probe, [IO.Compression.CompressionMode]::Decompress)
                $strict = $false
                try { $probeDecoder.CopyTo([IO.Stream]::Null) }
                catch { $strict = $_.Exception.InnerException -is [IO.InvalidDataException] }
                finally { $probeDecoder.Dispose(); $probe.Dispose() }
                if (-not $strict) { throw 'Strict compression validation unavailable; run in a fresh supported PowerShell process' }
                $script:OracleCdcStrictCompressionValidated = $true
            }
            $decoder = [IO.Compression.GZipStream]::new($inputStream, [IO.Compression.CompressionMode]::Decompress, $true)
            $decoded = [IO.MemoryStream]::new()
            try {
                $buffer = [byte[]]::new(65536)
                try {
                    while (($read = $decoder.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        if ($decoded.Length + $read -gt $limit) { throw 'Production asset exceeds the 64 MiB scan limit' }
                        $decoded.Write($buffer, 0, $read)
                    }
                } catch {
                    if ($_.Exception.Message -like '*Production asset exceeds*') { throw }
                    throw "Malformed compressed production asset: $Name"
                }
                if ($decoded.Length -ne $expectedSize) { throw "Malformed compressed production asset: $Name" }
                $decoded.Position = 0
                Assert-OracleCdcNoFixtureIdentity -Stream $decoded
            } finally { $decoder.Dispose(); $decoded.Dispose() }
        } elseif ($Name.EndsWith('.br', [StringComparison]::OrdinalIgnoreCase)) {
            $capacity = 65536
            $counterpart = $Path.Substring(0, $Path.Length - 3)
            if ([IO.File]::Exists($counterpart)) { $capacity = [int][Math]::Min($limit + 1, [Math]::Max(1, (Get-Item -LiteralPath $counterpart).Length + 1)) }
            while ($true) {
                $buffer = [byte[]]::new($capacity)
                $decoder = [IO.Compression.BrotliDecoder]::new()
                $consumed = 0
                $written = 0
                try { $status = $decoder.Decompress($encoded, $buffer, [ref]$consumed, [ref]$written) }
                catch { throw "Malformed compressed production asset: $Name" }
                finally { $decoder.Dispose() }
                if ($status -eq [Buffers.OperationStatus]::Done) {
                    if ($consumed -ne $encoded.Length) { throw "Malformed compressed production asset: $Name" }
                    if ($written -gt $limit) { throw 'Production asset exceeds the 64 MiB scan limit' }
                    $decoded = [IO.MemoryStream]::new($buffer, 0, $written, $false)
                    try { Assert-OracleCdcNoFixtureIdentity -Stream $decoded } finally { $decoded.Dispose() }
                    break
                }
                if ($status -ne [Buffers.OperationStatus]::DestinationTooSmall) { throw "Malformed compressed production asset: $Name" }
                if ($capacity -gt $limit) { throw 'Production asset exceeds the 64 MiB scan limit' }
                $capacity = [int][Math]::Min($limit + 1, $capacity * 2)
            }
        }
    } finally { $inputStream.Dispose() }
}

function Get-OracleCdcDistManifest {
    param([string]$Repository, [string]$Dist)
    $distRoot = Assert-OracleCdcRepositoryPath -Repository $Repository -Path $Dist
    $index = Assert-OracleCdcRepositoryPath -Repository $Repository -Path (Join-Path $distRoot 'index.html')
    if (-not [IO.File]::Exists($index)) { throw 'Production dist requires index.html' }
    $entrypoint = [IO.File]::ReadAllText($index)
    $moduleFound = $false
    foreach ($tag in [regex]::Matches($entrypoint, '<script\b[^>]*>', [Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
        if ($tag.Value -notmatch '\btype\s*=\s*["'']module["'']') { continue }
        $source = [regex]::Match($tag.Value, '\bsrc\s*=\s*["'']([^"'']+)["'']')
        if (-not $source.Success) { continue }
        $relative = $source.Groups[1].Value.Split('?')[0].TrimStart('/')
        if ($relative.StartsWith('${basePath}', [StringComparison]::Ordinal)) { $relative = $relative.Substring(11) }
        if ($relative.StartsWith('./', [StringComparison]::Ordinal)) { $relative = $relative.Substring(2) }
        if ($relative -notmatch '^assets/[^:?#]+\.js$' -or $relative.Split('/') -contains '..') {
            throw 'Production UI entrypoint must reference a local assets JavaScript module'
        }
        $module = Assert-OracleCdcRepositoryPath -Repository $Repository -Path (Join-Path $distRoot $relative)
        if (-not [IO.File]::Exists($module)) { throw 'Production UI entrypoint JavaScript is missing' }
        $moduleFound = $true
    }
    if (-not $moduleFound) { throw 'Production UI entrypoint must reference a local assets JavaScript module' }
    $directories = [Collections.Generic.Stack[string]]::new()
    $directories.Push($distRoot)
    $files = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    while ($directories.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $directories.Pop() -Force) {
            $full = Assert-OracleCdcRepositoryPath -Repository $Repository -Path $item.FullName
            if ($item.PSIsContainer) { $directories.Push($full); continue }
            $relative = $full.Substring($distRoot.Length + 1).Replace('\', '/')
            if ($relative -match '[\x00-\x1f\x7f]') { throw 'Control characters are forbidden in production asset names' }
            if ($relative.IndexOf('synthetic-ui-review', [StringComparison]::OrdinalIgnoreCase) -ge 0) { throw 'Fixture identity synthetic-ui-review is forbidden in production dist' }
            $files.Add($relative, $full)
        }
    }
    $names = [string[]]@($files.Keys)
    [Array]::Sort($names, [StringComparer]::Ordinal)
    $canonical = [Text.StringBuilder]::new("oracle-cdc-production-dist-v1`n")
    $records = [Collections.Generic.List[object]]::new()
    foreach ($name in $names) {
        $path = $files[$name]
        Assert-OracleCdcAssetContents -Path $path -Name $name
        $length = (Get-Item -LiteralPath $path).Length
        $hash = Get-OracleCdcFileSha256 -Path $path
        [void]$canonical.Append($name).Append("`t").Append($length.ToString([Globalization.CultureInfo]::InvariantCulture)).Append("`t").Append($hash).Append("`n")
        $records.Add([pscustomobject]@{ path = $name; length = $length; sha256 = $hash })
    }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($canonical.ToString())
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
    return [pscustomobject]@{ algorithm = 'oracle-cdc-production-dist-v1'; sha256 = $hash; canonical = $canonical.ToString(); files = $records.ToArray() }
}
[AppContext]::SetSwitch('System.IO.Compression.UseStrictValidation', $true)
$script:OracleCdcStrictCompressionValidated = $false
