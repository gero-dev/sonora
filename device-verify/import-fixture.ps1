<#
.SYNOPSIS
  Drives the Sonora "import playlist from file" flow over ADB and reports the result.

.DESCRIPTION
  Runs one fixture end to end: launch, navigate Settings -> "Импорт из других платформ"
  -> import button, pick the file through the SAF picker, then wait for matching and
  saving to finish and print the banner plus every network search from logcat.

  Notes on this device, learned the hard way:
   * The import section is an accordion whose state persists between launches, and its
     chevron is a SIBLING of the header text. The chevron is identified by matching its
     Y against the header's, because content-desc "Развернуть" appears once per section.
   * When the section is expanded the import button sits below the fold, so the Settings
     list has to be scrolled before the button can be tapped.
   * MainActivity can take >10 s to render after a force-stop; the launcher is focused in
     the meantime, so the script waits for the app window instead of sleeping a fixed time.

  Every adb call goes through Invoke-Adb with an explicit array: see the comment on that
  function for why a ValueFromRemainingArguments parameter silently corrupts arguments.

.PARAMETER Fixture
  File name as it appears on the device, e.g. spotify-liked.csv

.PARAMETER TimeoutSec
  Overall budget for the import (matching + saving). Each network search takes ~6.5 s.

.EXAMPLE
  .\import-fixture.ps1 -Fixture plain-titles.m3u
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string] $Fixture,
  [int] $TimeoutSec = 420,
  [string] $Adb = 'C:\Program Files\platform-tools\adb.exe',
  [string] $DeviceDir = '/sdcard/Download/SonoraTest'
)

$ErrorActionPreference = 'Stop'
$script:AdbPath = $Adb

function Invoke-Adb {
  # Arguments must arrive as ONE array and be splatted, so call sites look like
  #   Invoke-Adb -ArgList @('shell','monkey','-p','app.sonora')
  #
  # Do NOT change this to a ValueFromRemainingArguments parameter. PowerShell's binder
  # swallows unprefixed tokens that look like parameter names, so
  #   Invoke-Adb shell monkey -p app.sonora -c android.intent.category.LAUNCHER 1
  # reaches adb as [-c, android.intent.category.LAUNCHER, 1] with "-p app.sonora"
  # silently dropped. The symptom was a monkey run that injected its event into whatever
  # app was in front while the script believed it had launched Sonora.
  param([Parameter(Mandatory = $true)][string[]] $ArgList)
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $script:AdbPath @ArgList 2>&1
    return @($out | ForEach-Object { $_.ToString() })
  } finally {
    $ErrorActionPreference = $old
  }
}

function Get-UiXml {
  Invoke-Adb -ArgList @('shell','uiautomator','dump','/sdcard/ui.xml') | Out-Null
  (Invoke-Adb -ArgList @('shell','cat','/sdcard/ui.xml')) -join ''
}

function Get-Nodes {
  # Parses each <node> element and takes text, falling back to content-desc when text is
  # blank. Reading both attributes from one regex does not work: a chevron node is
  # <node text="" content-desc="Развернуть" ...>, so a single (?:text|content-desc)
  # alternation matches the empty text first and the node is discarded as blank — which
  # is exactly how an "expanded" section looked "collapsed" and sent the earlier version
  # of this script into a loop.
  param([string] $Xml)
  $out = @()
  foreach ($m in [regex]::Matches($Xml, '<node\b[^>]*>')) {
    $node = $m.Value
    $b = [regex]::Match($node, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $b.Success) { continue }
    $t = [regex]::Match($node, '(?:\s|^)text="([^"]*)"').Groups[1].Value
    if ([string]::IsNullOrWhiteSpace($t)) {
      $t = [regex]::Match($node, '(?:\s|^)content-desc="([^"]*)"').Groups[1].Value
    }
    if ([string]::IsNullOrWhiteSpace($t)) { continue }
    $out += [PSCustomObject]@{
      Text = $t
      X    = [int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2)
      Y    = [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)
    }
  }
  return $out
}

function Tap-At {
  param([int] $X, [int] $Y, [string] $Why)
  Write-Host ("  tap {0,-38} @{1},{2}" -f $Why, $X, $Y)
  Invoke-Adb -ArgList @('shell','input','tap',"$X","$Y") | Out-Null
  Start-Sleep -Milliseconds 1200
}

function Tap-Text {
  param([string] $Text, [string] $Why, [int] $MaxY = 99999)
  $hit = Get-Nodes (Get-UiXml) | Where-Object { $_.Text -eq $Text -and $_.Y -lt $MaxY } | Select-Object -First 1
  if (-not $hit) { return $false }
  Tap-At -X $hit.X -Y $hit.Y -Why $Why
  return $true
}

function Get-Banner {
  $texts = Get-Nodes (Get-UiXml) | ForEach-Object { $_.Text }
  $b = $texts | Where-Object { $_ -match '^(Готово|Ошибка)' } | Select-Object -First 1
  if ($b) { return $b }
  $e = $texts | Where-Object { $_ -match 'не найдено треков|Не удалось|не нашлось' } | Select-Object -First 1
  if ($e) { return $e }
  return $null
}

function Wait-ForApp {
  param([int] $TimeoutSec = 45)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $focus = (Invoke-Adb -ArgList @('shell','dumpsys','window')) | Select-String 'mCurrentFocus'
    if ($focus -and $focus.ToString() -match 'app\.sonora') {
      if (@(Get-Nodes (Get-UiXml) | ForEach-Object { $_.Text }) -contains 'SONORA') { return $true }
    }
    Start-Sleep -Seconds 2
  }
  return $false
}

# --- preflight -------------------------------------------------------------
$devLine = (Invoke-Adb -ArgList @('devices')) | Where-Object { $_ -match '\sdevice$' } | Select-Object -First 1
if (-not $devLine) { throw 'No authorised device.' }
$serial = ($devLine -split '\s+')[0]
if (((Invoke-Adb -ArgList @('shell','ls',$DeviceDir)) -join "`n") -notmatch [regex]::Escape($Fixture)) {
  throw "Fixture '$Fixture' is not in $DeviceDir."
}

Write-Host "=== import '$Fixture' (serial $serial) ==="

Invoke-Adb -ArgList @('logcat','-c') | Out-Null
Invoke-Adb -ArgList @('shell','am','force-stop','app.sonora') | Out-Null
Invoke-Adb -ArgList @('shell','monkey','-p','app.sonora','-c','android.intent.category.LAUNCHER','1') | Out-Null
if (-not (Wait-ForApp)) { throw 'app.sonora did not come to the foreground.' }

# --- Settings -> expand the import section ----------------------------------
if (-not (Tap-Text -Text 'Настройки' -Why 'open Settings' -MaxY 2700)) { throw 'No Settings tab.' }
Start-Sleep -Seconds 3

$expanded = $false
for ($i = 0; $i -lt 4; $i++) {
  $nodes = Get-Nodes (Get-UiXml)
  $header = $nodes | Where-Object { $_.Text -eq 'Импорт из других платформ' } | Select-Object -First 1
  if (-not $header) { break }
  $chev = $nodes |
    Where-Object { ($_.Text -eq 'Развернуть' -or $_.Text -eq 'Свернуть') -and [Math]::Abs($_.Y - $header.Y) -lt 45 } |
    Select-Object -First 1
  if ($chev -and $chev.Text -eq 'Свернуть') { $expanded = $true; break }
  if ($chev) { Tap-At -X $chev.X -Y $chev.Y -Why 'expand import section' }
  else       { Tap-At -X $header.X -Y $header.Y -Why 'toggle import section' }
  Start-Sleep -Seconds 2
}
if (-not $expanded) { throw 'Could not expand the import section.' }
Write-Host '  import section is expanded'

# --- scroll until the import button is on screen ----------------------------
$clicked = $false
for ($i = 0; $i -lt 8; $i++) {
  if (Tap-Text -Text 'Импортировать плейлист из файла' -Why 'start import') { $clicked = $true; break }
  Write-Host '  (import button below the fold; scrolling)'
  Invoke-Adb -ArgList @('shell','input','swipe','632','2100','632','1100','300') | Out-Null
  Start-Sleep -Seconds 2
}
if (-not $clicked) { throw 'Could not reach the import button.' }
Start-Sleep -Seconds 6

# --- SAF picker -> the fixture ----------------------------------------------
$visible = $false
for ($i = 0; $i -lt 12; $i++) {
  $texts = @(Get-Nodes (Get-UiXml) | ForEach-Object { $_.Text })
  if ($texts -contains $Fixture) { $visible = $true; break }

  if (Tap-Text -Text 'SonoraTest' -Why 'open SonoraTest folder') { continue }
  if (Tap-Text -Text 'Download'   -Why 'open Download folder')   { continue }
  if (Tap-Text -Text 'Все файлы'  -Why 'open All files')         { continue }
  if (Tap-Text -Text 'Файлы'      -Why 'Files tab' -MaxY 340)    { continue }

  Write-Host "  (picker: nothing to do; screen: $($texts -join ' | '))"
  Start-Sleep -Seconds 2
}
if (-not $visible) { throw "Could not reach '$Fixture' in the picker." }

if (-not (Tap-Text -Text $Fixture -Why "select $Fixture")) { throw "Could not tap '$Fixture'." }

Write-Host "  selected; waiting for import (budget ${TimeoutSec}s)"
Start-Sleep -Seconds 6

# --- wait for completion ----------------------------------------------------
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$banner = $null
while ((Get-Date) -lt $deadline) {
  $banner = Get-Banner
  if ($banner -match '^Готово') {
    $m = [regex]::Match($banner, 'сохранено\s+(\d+)\s+из\s+(\d+)')
    if (-not $m.Success) { break }                                     # nothing to save
    if ([int]$m.Groups[1].Value -ge [int]$m.Groups[2].Value) { break }  # all saved
  }
  Start-Sleep -Seconds 5
}

# --- report -----------------------------------------------------------------
Write-Host ''
Write-Host '=== RESULT ==='
Write-Host ("banner   : {0}" -f $banner)
$searches = @((Invoke-Adb -ArgList @('logcat','-d','-v','time')) | Select-String -Pattern 'SearchSpeed')
Write-Host ("searches : {0}" -f $searches.Count)
foreach ($s in $searches) { Write-Host ("   {0}" -f $s.Line.Trim()) }
Write-Host ("crashes  : {0}" -f @((Invoke-Adb -ArgList @('logcat','-d','-v','time')) | Select-String -Pattern 'FATAL EXCEPTION').Count)
