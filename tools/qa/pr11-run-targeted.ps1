# Run targeted Android visual QA for PR 11 refinements.
[CmdletBinding()]
param(
    [string]$CandidateApk = 'uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk',
    [string]$CandidateTestApk = 'uhabits-android/build/outputs/apk/androidTest/debug/uhabits-android-debug-androidTest.apk',
    [string]$Output = 'scratch/pr11/runtime-targeted-01',
    [int]$Port = 5580
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo

foreach ($item in @($CandidateApk, $CandidateTestApk)) {
    if (!(Test-Path -LiteralPath $item -PathType Leaf)) { throw "Missing APK: $item" }
}
$CandidateApk = (Resolve-Path $CandidateApk).Path
$CandidateTestApk = (Resolve-Path $CandidateTestApk).Path

$Output = [IO.Path]::GetFullPath((Join-Path $repo $Output))
if (!$Output.StartsWith((Join-Path $repo 'scratch/pr11'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must stay in scratch/pr11' }
if (Test-Path -LiteralPath $Output) { throw 'Use a new output directory; never overwrite previous evidence' }

$drive = Get-PSDrive D
if ($drive.Free -lt 8GB) { throw 'Temporary AVD requires at least 8 GB free on D' }
New-Item -ItemType Directory -Path $Output | Out-Null

$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'D:\DevData\Android\Sdk\platform-tools\adb.exe' }
if (!(Test-Path -LiteralPath $adb)) { throw 'adb must point to the existing Android SDK' }

$appPackage = 'org.isoron.uhabits.plus'
$runner = "$appPackage.test/org.isoron.uhabits.activities.tasks.TaskDraftQaRunner"
$class = 'org.isoron.uhabits.activities.tasks.TaskCalendarVisualTest'

& (Join-Path $PSScriptRoot 'with-avd.ps1') -Temporary -Headless -Port $Port -Sdk 'D:\DevData\Android\Sdk' -Action {
    param($serial)
    $appPackage = 'org.isoron.uhabits.plus'
    function Adb([string[]]$Arguments) {
        if ($Arguments.Length -gt 0 -and $Arguments[0] -eq 'shell') {
            $Arguments = @('shell', '-n', '-T') + $Arguments[1..($Arguments.Length - 1)]
        }
        $prev = $ErrorActionPreference
        $result = try {
            $ErrorActionPreference = 'Continue'
            $null | & $adb -s $serial @Arguments 2>&1
        } finally {
            $ErrorActionPreference = $prev
        }
        if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($result -join ' ')" }
        return $result
    }
    $avd = (Adb @('shell', 'getprop', 'ro.boot.qemu.avd_name') | Out-String).Trim()
    if ($avd -notmatch '^UHabit_Temp_[a-f0-9]+$') { throw 'Only wrapper-owned temporary AVDs allowed' }

    function Instrument([string]$Selection, [string]$Phase, [string[]]$Extra = @(), [string]$TargetRunner = $null) {
        Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable') | Out-Null
        Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'wifi_on', '0') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'wifi_scan_always_enabled', '0') | Out-Null
        $r = if ($TargetRunner) { $TargetRunner } else {
            $line = (Adb @('shell', 'pm', 'list', 'instrumentation') | Where-Object { $_ -match [regex]::Escape($appPackage) } | Select-Object -First 1)
            if ($line -match 'instrumentation:([^ ]+)') { $matches[1] } else { $runner }
        }
        $result = Adb (@('shell', 'am', 'instrument', '-w', '-r', '-e', 'authorizedAvd', $avd, '-e', 'class', $Selection, '-e', 'phase', $Phase) + $Extra + @($r))
        $result | Set-Content -LiteralPath (Join-Path $Output "$Phase-instrumentation.txt") -Encoding UTF8
        $joined = $result -join "`n"
        if ($joined -notmatch 'OK \(1 test\)' -or $joined -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed: $Phase" }
    }

    try {
        $deadline = (Get-Date).AddSeconds(15)
        do {
            Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
            Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable') | Out-Null
            Adb @('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1') | Out-Null
            Adb @('shell', 'settings', 'put', 'global', 'wifi_on', '0') | Out-Null
            Start-Sleep -Milliseconds 500
            $airplane = (Adb @('shell', 'settings', 'get', 'global', 'airplane_mode_on') | Out-String).Trim()
            $wifi = (Adb @('shell', 'settings', 'get', 'global', 'wifi_on') | Out-String).Trim()
        } until (($airplane -eq '1' -and $wifi -eq '0') -or (Get-Date) -gt $deadline)

        Adb @('shell', 'cmd', 'alarm', 'set-timezone', 'Asia/Novosibirsk') | Out-Null
        Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null

        Adb @('install', $CandidateApk) | Out-Null
        Adb @('install', $CandidateTestApk) | Out-Null
        Adb @('shell', 'pm', 'grant', $appPackage, 'android.permission.POST_NOTIFICATIONS') | Out-Null

        Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
        Adb @('shell', 'input', 'keyevent', '82') | Out-Null
        Start-Sleep -Seconds 2

        # 1. Light theme campaign (5 scenarios)
        Instrument "$class#targetedVisualVerification" 'refinements-light' @('-e', 'freshFixture', 'true')

        # 2. Narrow screen with font scale 1.3x (1 scenario)
        Adb @('shell', 'wm', 'size', '900x2000') | Out-Null
        Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.3') | Out-Null
        Instrument "$class#targetedVisualVerification" 'refinements-narrow13'
        Adb @('shell', 'wm', 'size', 'reset') | Out-Null
        Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.0') | Out-Null

    } finally {
        $prev = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null | & $adb -s $serial pull "/sdcard/Android/data/$appPackage/files/pr11" $Output 2>&1 | Out-File (Join-Path $Output 'pull.txt') -Encoding UTF8
            $null | & $adb -s $serial logcat -d -t 2500 2>&1 | Out-File (Join-Path $Output 'logcat.txt') -Encoding UTF8
            $null | & $adb -s $serial shell -n -T wm size reset 2>$null | Out-Null
            $null | & $adb -s $serial shell -n -T settings put system font_scale 1.0 2>$null | Out-Null
        } finally {
            $ErrorActionPreference = $prev
        }
    }
}

if ($LASTEXITCODE -ne 0) { throw 'Temporary AVD campaign failed' }
Write-Output "PR11 targeted runtime artifacts: $Output"
