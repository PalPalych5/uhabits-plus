[CmdletBinding()]
param(
    [string]$CandidateApk = 'uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk',
    [string]$CandidateTestApk = 'uhabits-android/build/outputs/apk/androidTest/debug/uhabits-android-debug-androidTest.apk',
    [string]$Output = '',
    [int]$Port = 5588
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo

foreach ($item in @($CandidateApk, $CandidateTestApk)) {
    if (!(Test-Path -LiteralPath $item -PathType Leaf)) { throw "Missing APK: $item" }
}
$CandidateApk = (Resolve-Path $CandidateApk).Path
$CandidateTestApk = (Resolve-Path $CandidateTestApk).Path

if (!$Output) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Output = "D:\DevData\TestArtifacts\targeted-qa-api35-$timestamp"
}
$Output = [IO.Path]::GetFullPath($Output)
if ($Output.Substring(0, 3) -ine 'D:\') { throw 'Targeted QA artifacts must reside on D:' }
if (Test-Path -LiteralPath $Output) { throw "Refusing existing output path: $Output" }

$drive = Get-PSDrive D
if ($drive.Free -lt 8GB) { throw 'Temporary AVD requires at least 8 GB free on D' }
New-Item -ItemType Directory -Path $Output | Out-Null

$adb = 'D:\DevData\Android\Sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw "Missing adb tool at $adb" }

$runner = 'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner'
$testClasses = @(
    'org.isoron.uhabits.qa.TargetedHabitInteractionAndroidTest',
    'org.isoron.uhabits.notifications.NotificationsEffectiveAndroidTest#notificationActionsPersistAndDefaultNotificationIsDismissible',
    'org.isoron.uhabits.activities.habits.show.timer.PomodoroRecoveryAndroidTest#overtimeCupStartsRunningBreakOnlyOnce'
)
$phase1Test = 'org.isoron.uhabits.activities.habits.show.timer.PomodoroRecoveryAndroidTest#phase1_startPomodoroBreakAndRecordPidBeforeProcessKill'
$phase2Test = 'org.isoron.uhabits.activities.habits.show.timer.PomodoroRecoveryAndroidTest#phase2_verifyPomodoroBreakRecoveredInNewProcessAfterKill'

Write-Host "Starting isolated API 35 AVD for targeted Android QA on port $Port..."
& (Join-Path $PSScriptRoot 'with-avd.ps1') -Temporary -Headless -Port $Port -Sdk 'D:\DevData\Android\Sdk' -Action {
    param($serial)
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

    function Adb-Lenient([string[]]$Arguments) {
        if ($Arguments.Length -gt 0 -and $Arguments[0] -eq 'shell') {
            $Arguments = @('shell', '-n', '-T') + $Arguments[1..($Arguments.Length - 1)]
        }
        $prev = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null | & $adb -s $serial @Arguments 2>&1
        } finally {
            $ErrorActionPreference = $prev
        }
    }

    function Run-TargetedTest([string]$tc) {
        Write-Host "Executing targeted instrumentation: $tc..."
        Adb-Lenient @('shell', 'svc', 'power', 'stayon', 'true') | Out-Null
        Adb-Lenient @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        Adb-Lenient @('shell', 'wm', 'dismiss-keyguard') | Out-Null
        $cleanName = ($tc -replace '[^A-Za-z0-9_.-]', '_')
        $log = Adb @('shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', $tc,
            $runner)
        $logText = $log -join "`n"
        $logText | Set-Content -LiteralPath (Join-Path $Output "$cleanName-output.txt") -Encoding utf8

        $passed = $logText -match 'OK \(\d+ tests?\)' -and $logText -notmatch 'FAILURES|INSTRUMENTATION_FAILED|Process crashed'
        if (!$passed) {
            Write-Error "Test execution FAILED for ${tc}:`n$logText"
            throw "Targeted QA test failed: $tc"
        }
        Write-Host "PASS: $tc"
    }

    $avd = (Adb @('shell', 'getprop', 'ro.boot.qemu.avd_name') | Out-String).Trim()
    if ($avd -notmatch '^UHabit_Temp_[a-f0-9]+$') { throw "Unexpected AVD name: $avd" }
    Write-Host "Connected to isolated temporary AVD: $avd ($serial)"

    try {
        Write-Host "Configuring isolated device environment (airplane mode, notifications, stay on)..."
        Adb @('shell', 'svc', 'power', 'stayon', 'true') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '3') | Out-Null
        Adb @('shell', 'settings', 'put', 'system', 'screen_off_timeout', '1800000') | Out-Null
        Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable') | Out-Null
        Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'wifi_on', '0') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'wifi_scan_always_enabled', '0') | Out-Null

        Write-Host "Installing candidate APK: $CandidateApk..."
        Adb @('install', '-r', '-g', $CandidateApk) | Out-Null
        Write-Host "Installing candidate test APK: $CandidateTestApk..."
        Adb @('install', '-r', '-g', $CandidateTestApk) | Out-Null

        Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
        Adb @('shell', 'pm', 'grant', 'org.isoron.uhabits.plus', 'android.permission.POST_NOTIFICATIONS') | Out-Null

        # Clear logcat so only fresh test and perf logs are captured
        Adb @('shell', 'logcat', '-c') | Out-Null

        # 1. Run main test classes
        foreach ($tc in $testClasses) {
            Run-TargetedTest $tc
        }

        # 2. GAP-10 True OS Process Death Two-Phase Test
        Write-Host "=== Phase F GAP-10: True OS Process Death Verification ==="
        Run-TargetedTest $phase1Test

        Write-Host "Killing app process to enforce true OS process termination..."
        Adb-Lenient @('shell', 'am', 'kill', 'org.isoron.uhabits.plus') | Out-Null
        Adb-Lenient @('shell', 'killall', '-9', 'org.isoron.uhabits.plus') | Out-Null
        Start-Sleep -Seconds 2

        Write-Host "Running Phase 2 in a fresh OS process..."
        Run-TargetedTest $phase2Test
        Write-Host "GAP-10 True OS Process Death Verification passed!"

        # 3. Cold Start and UI Profiling Metrics
        Write-Host "=== Phase E Startup & UI Profiling ==="
        Write-Host "Measuring cold start latency via am start -W..."
        Adb-Lenient @('shell', 'am', 'force-stop', 'org.isoron.uhabits.plus') | Out-Null
        Start-Sleep -Seconds 1
        $coldStartOutput = Adb @('shell', 'am', 'start', '-W', '-n', 'org.isoron.uhabits.plus/org.isoron.uhabits.MainActivity')
        Write-Host ($coldStartOutput -join "`n")
        $coldStartOutput | Set-Content -LiteralPath (Join-Path $Output 'cold-start-output.txt') -Encoding utf8

        Write-Host "Extracting SPRINT03_ANDROID_PERF metrics from logcat..."
        $perfLines = Adb @('shell', 'logcat', '-d', '-s', 'SPRINT03_ANDROID_PERF')

        $artifactsRoot = 'D:\DevData\TestArtifacts'
        if (!(Test-Path -LiteralPath $artifactsRoot)) {
            New-Item -ItemType Directory -Path $artifactsRoot | Out-Null
        }
        $perfMetricsPath = Join-Path $artifactsRoot 'sprint03-android-perf-metrics.txt'
        $metricsReport = @(
            "=== COLD START METRICS (am start -W) ===",
            ($coldStartOutput -join "`r`n"),
            "",
            "=== SPRINT03 ANDROID PERF METRICS (logcat SPRINT03_ANDROID_PERF) ===",
            ($perfLines -join "`r`n")
        ) -join "`r`n"

        $metricsReport | Set-Content -LiteralPath $perfMetricsPath -Encoding utf8
        $metricsReport | Set-Content -LiteralPath (Join-Path $Output 'sprint03-android-perf-metrics.txt') -Encoding utf8
        Write-Host "Performance metrics saved to $perfMetricsPath"

        Write-Host "All targeted Android instrumentation QA passed successfully!"
    } finally {
        Write-Host "Collecting device diagnostics to $Output..."
        try {
            Adb @('shell', 'logcat', '-d', '-t', '5000') | Set-Content -LiteralPath (Join-Path $Output 'logcat.txt') -Encoding utf8
        } catch {
            Write-Warning "Failed to capture logcat: $_"
        }
        Adb-Lenient @('shell', 'am', 'force-stop', 'org.isoron.uhabits.plus') | Out-Null
    }
}

Write-Host "Targeted Android QA successfully finished. Evidence directory: $Output"
