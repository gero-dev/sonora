#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Push the Sonora import fixture corpus to the connected Android device and
    verify every file landed with the correct byte size.

.DESCRIPTION
    Copies every file from device-verify/import-e2e/ into
    /sdcard/Download/SonoraTest/ on the device, then re-reads each file's size
    from the device with `stat -c %s` and compares it against the local length.

    Idempotent: `adb push` overwrites in place and `mkdir -p` is a no-op when
    the directory already exists, so the script can be re-run any number of
    times with the same result.

    Target shell is PowerShell 7 (pwsh). The script deliberately avoids
    PS7-only syntax so it also runs under Windows PowerShell 5.1; see the
    version notice it prints. It fails loudly and returns a non-zero exit code
    instead of continuing on error.

.PARAMETER Serial
    adb device serial. Defaults to the PKG110 test device.

.PARAMETER Adb
    Full path to adb.exe.

.PARAMETER RemoteDir
    Destination directory on the device. This script only ever creates or
    overwrites files inside this directory.

.PARAMETER FixtureDir
    Local fixture directory. Defaults to <script dir>/import-e2e.

.OUTPUTS
    Exit code 0 = every fixture verified. 1 = at least one failure.
    2 = device unreachable / not authorised.

.EXAMPLE
    pwsh -File .\device-verify\push-fixtures.ps1
#>
[CmdletBinding()]
param(
    [string]$Serial = '3B6F6TE910B2Q59D',
    [string]$Adb = 'C:\Program Files\platform-tools\adb.exe',
    [string]$RemoteDir = '/sdcard/Download/SonoraTest',
    [string]$FixtureDir = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------

$script:AdbPath = $Adb

if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Warning ("Running on Windows PowerShell {0}. This script targets PowerShell 7 " +
        "(pwsh); install it for the supported configuration." -f $PSVersionTable.PSVersion)
    Write-Host ''
}

if (-not (Test-Path -LiteralPath $script:AdbPath -PathType Leaf)) {
    Write-Host "FATAL: adb not found at '$script:AdbPath'." -ForegroundColor Red
    exit 1
}

if ([string]::IsNullOrWhiteSpace($FixtureDir)) {
    $FixtureDir = Join-Path $PSScriptRoot 'import-e2e'
}
if (-not (Test-Path -LiteralPath $FixtureDir -PathType Container)) {
    Write-Host "FATAL: fixture directory not found: '$FixtureDir'." -ForegroundColor Red
    exit 1
}

<#
    Runs adb and returns its merged stdout/stderr plus the real exit code.
    Native stderr must not be allowed to trip $ErrorActionPreference='Stop',
    so the preference is relaxed only for the duration of the call and the
    exit code is inspected explicitly by the caller instead.
#>
function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $script:AdbPath @Arguments 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{
        Lines    = @($raw | ForEach-Object { "$_" })
        ExitCode = $code
    }
}

# ---------------------------------------------------------------------------
# Device reachability
# ---------------------------------------------------------------------------

Write-Host '=== Sonora import fixtures: push + verify ===' -ForegroundColor Cyan
Write-Host ("adb      : {0}" -f $script:AdbPath)
Write-Host ("serial   : {0}" -f $Serial)
Write-Host ("local    : {0}" -f (Resolve-Path -LiteralPath $FixtureDir).Path)
Write-Host ("remote   : {0}" -f $RemoteDir)
Write-Host ''

$devices = Invoke-Adb -Arguments @('-s', $Serial, 'get-state')
if ($devices.ExitCode -ne 0 -or ($devices.Lines -join ' ').Trim() -ne 'device') {
    Write-Host 'FATAL: device not reachable or not authorised.' -ForegroundColor Red
    Write-Host ("adb -s {0} get-state -> exit {1}" -f $Serial, $devices.ExitCode)
    $devices.Lines | ForEach-Object { Write-Host ("  | {0}" -f $_) }
    Write-Host ''
    Write-Host 'Run `adb devices` and confirm the device shows as "device" (not "unauthorized").'
    exit 2
}
Write-Host 'device state: device' -ForegroundColor Green
Write-Host ''

# ---------------------------------------------------------------------------
# Create the remote directory (idempotent)
# ---------------------------------------------------------------------------

$mkdir = Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'mkdir', '-p', $RemoteDir)
if ($mkdir.ExitCode -ne 0) {
    Write-Host ("FATAL: could not create '{0}' on the device." -f $RemoteDir) -ForegroundColor Red
    $mkdir.Lines | ForEach-Object { Write-Host ("  | {0}" -f $_) }
    exit 1
}

# ---------------------------------------------------------------------------
# Push and verify every fixture
# ---------------------------------------------------------------------------

$fixtures = @(Get-ChildItem -LiteralPath $FixtureDir -File | Sort-Object Name)
if ($fixtures.Count -eq 0) {
    Write-Host ("FATAL: no fixture files found in '{0}'." -f $FixtureDir) -ForegroundColor Red
    exit 1
}

$results = [System.Collections.Generic.List[object]]::new()

foreach ($file in $fixtures) {

    $remotePath = "$RemoteDir/$($file.Name)"
    $localSize = $file.Length

    $push = Invoke-Adb -Arguments @('-s', $Serial, 'push', $file.FullName, $remotePath)

    $remoteRaw = Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'stat', '-c', '%s', $remotePath)
    $remoteText = ($remoteRaw.Lines -join ' ').Trim()
    $remoteSize = 0
    $parsed = [int64]::TryParse($remoteText, [ref]$remoteSize)

    $pushOk = ($push.ExitCode -eq 0)
    $sizeOk = ($parsed -and $remoteSize -eq $localSize)
    $ok = $pushOk -and $sizeOk

    $results.Add([pscustomobject]@{
            Name       = $file.Name
            LocalSize  = $localSize
            RemoteSize = if ($parsed) { $remoteSize } else { $null }
            Pushed     = $pushOk
            SizeMatch  = $sizeOk
            Ok         = $ok
            Detail     = (($push.Lines | Where-Object { $_ -match '\S' }) -join ' / ')
        })

    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    $colour = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1}" -f $tag, $file.Name) -ForegroundColor $colour
    Write-Host ("        local  {0,10} bytes" -f $localSize)
    Write-Host ("        device {0,10} bytes" -f $(if ($parsed) { $remoteSize } else { '<unparsed>' }))
    if (-not $ok) {
        Write-Host ("        push exit {0}" -f $push.ExitCode) -ForegroundColor Red
        $push.Lines | Where-Object { $_ -match '\S' } | ForEach-Object {
            Write-Host ("        | {0}" -f $_) -ForegroundColor Red
        }
        if ($parsed -and $remoteSize -ne $localSize) {
            Write-Host ("        size mismatch: expected {0}, got {1}" -f $localSize, $remoteSize) -ForegroundColor Red
        }
        if (-not $parsed) {
            Write-Host ("        could not parse device size from: '{0}'" -f $remoteText) -ForegroundColor Red
        }
    }
    Write-Host ''
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

$passed = @($results | Where-Object { $_.Ok })
$failed = @($results | Where-Object { -not $_.Ok })

Write-Host '=== SUMMARY ===' -ForegroundColor Cyan
$results |
    Select-Object @{ n = 'Fixture'; e = { $_.Name } },
        @{ n = 'Local'; e = { $_.LocalSize } },
        @{ n = 'Device'; e = { $_.RemoteSize } },
        @{ n = 'Result'; e = { if ($_.Ok) { 'PASS' } else { 'FAIL' } } } |
    Format-Table -AutoSize |
    Out-String -Width 200 |
    Write-Host

Write-Host ("{0} pushed and verified, {1} failed, {2} total." -f $passed.Count, $failed.Count, $results.Count) `
    -ForegroundColor $(if ($failed.Count -eq 0) { 'Green' } else { 'Red' })

if ($failed.Count -gt 0) {
    Write-Host ''
    Write-Host 'Failed fixtures:' -ForegroundColor Red
    $failed | ForEach-Object { Write-Host ("  - {0}" -f $_.Name) -ForegroundColor Red }
    exit 1
}

$listing = Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'ls', '-l', $RemoteDir)
Write-Host ''
Write-Host ("=== device listing: {0} ===" -f $RemoteDir) -ForegroundColor Cyan
$listing.Lines | ForEach-Object { Write-Host $_ }

exit 0
