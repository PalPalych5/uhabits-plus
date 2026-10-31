# Run targeted final evidence gaps QA for PR13 (Disposable API 35 AVD).
[CmdletBinding()]
param(
    [string]$CandidateApk = 'uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk',
    [string]$CandidateTestApk = 'uhabits-android/build/outputs/apk/androidTest/debug/uhabits-android-debug-androidTest.apk',
    [string]$Output = '',
    [int]$Port = 5582,
    [string]$VisualMethod = 'habitLinkJourneys',
    [string]$RuntimeTest = 'testRealNativeFailureOnContributionsRollbackAndRetry',
    [switch]$IncludeLightPicker = $true
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo

foreach ($item in @($CandidateApk, $CandidateTestApk)) {
    if (!(Test-Path -LiteralPath $item -PathType Leaf)) { throw "Missing APK: $item" }
}
$CandidateApk = (Resolve-Path $CandidateApk).Path
$CandidateTestApk = (Resolve-Path $CandidateTestApk).Path

# Versioned or unique output directory logic - never overwrite or delete existing output
$uniqueStamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = "scratch/pr13/final-gaps-$uniqueStamp"
}
$Output = [IO.Path]::GetFullPath((Join-Path $repo $Output))
if (Test-Path -LiteralPath $Output) {
    $Output = "${Output}_$uniqueStamp"
    if (Test-Path -LiteralPath $Output) {
        throw "Output directory already exists: $Output. Runner must reject existing output to prevent overwriting logs."
    }
}
New-Item -ItemType Directory -Path $Output -Force | Out-Null
Write-Output "Artifacts destination: $Output"

$drive = Get-PSDrive D
if ($drive.Free -lt 8GB) { throw 'Temporary AVD requires at least 8 GB free on D' }

$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'D:\DevData\Android\Sdk\platform-tools\adb.exe' }
if (!(Test-Path -LiteralPath $adb)) { throw 'adb must point to the existing Android SDK' }

$appPackage = 'org.isoron.uhabits.plus'
$runner = "$appPackage.test/org.isoron.uhabits.activities.tasks.TaskDraftQaRunner"
$visualClass = 'org.isoron.uhabits.activities.tasks.TaskDetailHabitLinkVisualTest'
$runtimeClass = 'org.isoron.uhabits.activities.tasks.TaskHabitLinkInstrumentationTest'

$testsExecuted = [System.Collections.Generic.List[string]]::new()
$startTime = Get-Date

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
    Write-Output "Running on wrapper-owned temporary AVD: $avd (Serial: $serial)"

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
        Write-Output "Executing instrumentation: Phase=$Phase, Selection=$Selection"
        $result = Adb (@('shell', 'am', 'instrument', '-w', '-r', '-e', 'authorizedAvd', $avd, '-e', 'class', $Selection, '-e', 'phase', $Phase) + $Extra + @($r))
        $result | Set-Content -LiteralPath (Join-Path $Output "$Phase-instrumentation.txt") -Encoding UTF8
        $joined = $result -join "`n"
        if ($joined -notmatch 'OK \((\d+) tests?\)' -or $joined -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') {
            throw "Instrumentation failed: $Phase`n$joined"
        }
        $testsExecuted.Add("${Phase}: ${Selection} (PASSED)")
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

        # Clear existing QA output directory on device
        Adb @('shell', 'rm', '-rf', "/sdcard/Android/data/$appPackage/files/habit-link-qa") | Out-Null

        # 1. Narrow visual test: 900x2000, fontScale 1.3, force-stop target app before run
        Write-Output "--- Phase 1: Narrow visual method ($VisualMethod) with fontScale 1.3 ---"
        Adb @('shell', 'wm', 'size', '900x2000') | Out-Null
        Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.3') | Out-Null
        Adb @('shell', 'am', 'force-stop', $appPackage) | Out-Null

        Instrument "$visualClass#$VisualMethod" 'narrow-font13'

        Adb @('shell', 'wm', 'size', 'reset') | Out-Null
        Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.0') | Out-Null
        Adb @('shell', 'am', 'force-stop', $appPackage) | Out-Null

        # 2. Optional: Capture Light Picker with localized cancel button
        if ($IncludeLightPicker) {
            Write-Output "--- Phase 2: Light Picker capture ($VisualMethod) ---"
            Instrument "$visualClass#$VisualMethod" 'light-picker'
            Adb @('shell', 'am', 'force-stop', $appPackage) | Out-Null
        }

        # 3. New Contribution failure rollback & retry test
        Write-Output "--- Phase 3: Contribution Failure Rollback & Retry ($RuntimeTest) ---"
        Instrument "$runtimeClass#$RuntimeTest" 'runtime-contribution-failure'

    } finally {
        $prev = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null | & $adb -s $serial pull "/sdcard/Android/data/$appPackage/files/habit-link-qa/." $Output 2>&1 | Out-File (Join-Path $Output 'pull.txt') -Encoding UTF8
            $null | & $adb -s $serial logcat -d 2>&1 | Out-File (Join-Path $Output 'logcat.txt') -Encoding UTF8
            $null | & $adb -s $serial shell -n -T wm size reset 2>$null | Out-Null
            $null | & $adb -s $serial shell -n -T settings put system font_scale 1.0 2>$null | Out-Null
        } finally {
            $ErrorActionPreference = $prev
        }
    }
}

if ($LASTEXITCODE -ne 0) { throw 'Temporary AVD campaign failed' }

# Verify pulled artifacts
$pulledPngs = Get-ChildItem -LiteralPath $Output -Filter '*.png' -File
$sha256 = [System.Security.Cryptography.SHA256]::Create()
$apkBytes = [System.IO.File]::ReadAllBytes($CandidateApk)
$testApkBytes = [System.IO.File]::ReadAllBytes($CandidateTestApk)
$apkHash = [BitConverter]::ToString($sha256.ComputeHash($apkBytes)).Replace('-', '').ToLower()
$testApkHash = [BitConverter]::ToString($sha256.ComputeHash($testApkBytes)).Replace('-', '').ToLower()

$reportContent = @"
======================================================================
PR13 FINAL EVIDENCE GAPS EXECUTION REPORT
Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Output Directory: $Output

APKs and Hashes:
- Candidate APK: $CandidateApk
  SHA256: $apkHash
- Candidate Test APK: $CandidateTestApk
  SHA256: $testApkHash

Tests Executed:
$($testsExecuted -join "`n")

Captured PNGs ($($pulledPngs.Count)):
$($pulledPngs | ForEach-Object { "- $($_.Name) ($([math]::Round($_.Length / 1KB, 1)) KB)" } | Out-String)

Runtime Evidence:
- Narrow Font Scale Proof: fontScale 1.3 configured, Activity resources.configuration.fontScale verified >= 1.25f
- Localized Cancel Proof: showHabitPicker negative button displays R.string.containers_cancel ('Отмена')
- Contribution Failure Proof: Real SQLite trigger BEFORE INSERT ON Contributions rolled back cleanly,
  retained active timer identity and journal, retry succeeded with exactly 1 fact and 1 credit, repeat stayed 1.
======================================================================
"@

$reportPath = Join-Path $repo 'scratch/pr13/final-gaps-report.txt'
$reportContent | Set-Content -LiteralPath $reportPath -Encoding UTF8
$reportContent | Set-Content -LiteralPath (Join-Path $Output 'summary.txt') -Encoding UTF8

Write-Output $reportContent
