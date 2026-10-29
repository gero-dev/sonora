<#
.SYNOPSIS
    Запускает аудит стриминга YouTube (streaming-audit.py), при необходимости
    переключая Cloudflare WARP через warp-cli, и ГАРАНТИРОВАННО возвращает
    WARP в исходное состояние (finally), проверяя интернет.

.DESCRIPTION
    По умолчанию (режим full) выполняет три окна замеров:
        A  — WARP ON, быстрый baseline (без релеев);
        B+C — WARP OFF, реальная сеть РФ: все слои + yt-dlp + релеи
              (одно короткое окно без туннеля);
        D  — WARP ON, подтверждение восстановления.
    Python-скрипт сам переключает WARP и восстанавливает его; этот враппер
    страхует на случай падения/убийства процесса и печатает краткую сводку.

.PARAMETER Mode
    full (по умолчанию) | quick (только A+D) | custom.

.PARAMETER Warp
    Для custom: on | off | leave — желаемое состояние WARP на один запуск.

.PARAMETER Phase
    Метка фазы для custom-запуска.

.PARAMETER WithYtdlp
    Для custom: проверять yt-dlp.

.PARAMETER Quick
    Для custom: без релеев.

.PARAMETER ReportOnly
    Только пересобрать docs/PC-STREAMING-AUDIT.md из JSON (без сети).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/dev/run-streaming-audit.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/dev/run-streaming-audit.ps1 -Mode custom -Warp off -Phase B -WithYtdlp
#>
[CmdletBinding()]
param(
    [ValidateSet('full', 'quick', 'custom')]
    [string]$Mode = 'full',

    [ValidateSet('on', 'off', 'leave')]
    [string]$Warp = 'leave',

    [string]$Phase = '',

    [switch]$WithYtdlp,

    [switch]$Quick,

    [switch]$ReportOnly
)

$ErrorActionPreference = 'Continue'

$Root   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Py     = Join-Path $PSScriptRoot 'streaming-audit.py'
$JsonOut = Join-Path $Root 'docs\ci\pc-streaming-audit.json'
$MdOut   = Join-Path $Root 'docs\PC-STREAMING-AUDIT.md'
$WarpCli = 'C:\Program Files\Cloudflare\Cloudflare WARP\warp-cli.exe'

function Get-WarpStatus {
    if (-not (Test-Path -LiteralPath $WarpCli)) { return 'Unavailable' }
    $out = (& $WarpCli status 2>&1 | Out-String)
    if ($out -match 'Status update:\s*(\w+)') { return $Matches[1] }
    if ($out -match 'not running|unable') { return 'Unavailable' }
    return 'Unknown'
}

function Wait-Warp([string]$Target, [int]$TimeoutSec = 45) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Get-WarpStatus) -eq $Target) { return $true }
        Start-Sleep -Milliseconds 600
    }
    return ((Get-WarpStatus) -eq $Target)
}

function Test-Internet {
    for ($i = 0; $i -lt 4; $i++) {
        try {
            $r = Invoke-WebRequest -Uri 'https://api.ipify.org' -TimeoutSec 8 -UseBasicParsing
            if ($r.StatusCode -eq 200 -and $r.Content) { return $r.Content.Trim() }
        } catch { }
        Start-Sleep -Milliseconds 1500
    }
    return $null
}

if (-not (Test-Path -LiteralPath $Py)) {
    Write-Error "не найден скрипт: $Py"
    exit 2
}

$OriginalWarp = Get-WarpStatus
Write-Host "=== streaming audit: режим=$Mode, исходный WARP=$OriginalWarp ===" -ForegroundColor Cyan

$python = 'python'
$common = @($Py)
$exitCode = 0

if ($ReportOnly) {
    & $python @common '--report-only' '--json-out' $JsonOut '--md-out' $MdOut
    $exitCode = $LASTEXITCODE
}
else {
    $runs = @()
    if ($Mode -eq 'full') {
        $runs = @(
            @{ Phase = 'A (WARP ON, baseline)'; Warp = 'on';  Extra = @('--quick') },
            @{ Phase = 'B+C (WARP OFF: реальная сеть РФ + yt-dlp + релеи)'; Warp = 'off'; Extra = @('--with-ytdlp') },
            @{ Phase = 'D (WARP ON, восстановление)'; Warp = 'on'; Extra = @('--quick') }
        )
    }
    elseif ($Mode -eq 'quick') {
        $runs = @(
            @{ Phase = 'A (WARP ON, baseline)'; Warp = 'on'; Extra = @('--quick') },
            @{ Phase = 'D (WARP ON, восстановление)'; Warp = 'on'; Extra = @('--quick') }
        )
    }
    else {
        $extra = @()
        if ($WithYtdlp) { $extra += '--with-ytdlp' }
        if ($Quick)     { $extra += '--quick' }
        $runs = @(@{ Phase = $Phase; Warp = $Warp; Extra = $extra })
    }

    try {
        foreach ($r in $runs) {
            $args = @($Py, '--json-out', $JsonOut, '--md-out', $MdOut,
                      '--warp', $r.Warp)
            if ($r.Phase) { $args += @('--phase', $r.Phase) }
            $args += $r.Extra
            Write-Host ""
            Write-Host "--- запуск: фаза '$($r.Phase)', WARP=$($r.Warp), yt-dlp=$($r.Extra -contains '--with-ytdlp') ---" -ForegroundColor Yellow
            & $python @args
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "python завершился с кодом $LASTEXITCODE (продолжаем)"
                $exitCode = $LASTEXITCODE
            }
        }
    }
    finally {
        # ── страховка: вернуть WARP в исходное состояние ──────────────────
        $cur = Get-WarpStatus
        if ($OriginalWarp -in @('Connected', 'Disconnected') -and $cur -ne $OriginalWarp) {
            $action = if ($OriginalWarp -eq 'Connected') { 'connect' } else { 'disconnect' }
            Write-Host "=== WARP restore: $cur -> $OriginalWarp ($action) ===" -ForegroundColor Cyan
            & $WarpCli $action | Out-Null
            if (-not (Wait-Warp $OriginalWarp 60)) {
                Write-Warning "WARP не перешёл в $OriginalWarp, повторяю"
                & $WarpCli $action | Out-Null
                Wait-Warp $OriginalWarp 60 | Out-Null
            }
        }
        $ip = Test-Internet
        $finalWarp = Get-WarpStatus
        if ($ip) {
            Write-Host "=== интернет OK: egress $ip, WARP=$finalWarp ===" -ForegroundColor Green
        }
        else {
            Write-Warning "=== ИНТЕРНЕТ НЕ РАБОТАЕТ после восстановления! WARP=$finalWarp. Нужно вручную: & '$WarpCli' connect"
            if ($exitCode -eq 0) { $exitCode = 3 }
        }
        if ($finalWarp -ne $OriginalWarp) {
            Write-Warning "WARP=$finalWarp, ожидался $OriginalWarp"
            if ($exitCode -eq 0) { $exitCode = 3 }
        }
    }
}

Write-Host ""
Write-Host "=== итог ===" -ForegroundColor Cyan
Write-Host "  JSON: $JsonOut"
Write-Host "  MD:   $MdOut"
Write-Host "  WARP: исходно $OriginalWarp, сейчас $(Get-WarpStatus)"
exit $exitCode
