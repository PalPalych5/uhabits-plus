[CmdletBinding()]
param(
    [string]$CandidateApk = 'uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk',
    [string]$CandidateTestApk = 'uhabits-android/build/outputs/apk/androidTest/debug/uhabits-android-debug-androidTest.apk',
    [string]$Output = '',
    [int]$Port = 5584
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo

function Get-Sha256([string]$Path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $hashBytes = $sha.ComputeHash($stream)
        return [System.BitConverter]::ToString($hashBytes).Replace('-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $sha.Dispose()
    }
}

foreach ($item in @($CandidateApk, $CandidateTestApk)) {
    if (!(Test-Path -LiteralPath $item -PathType Leaf)) { throw "Missing APK: $item" }
}
$CandidateApk = (Resolve-Path $CandidateApk).Path
$CandidateTestApk = (Resolve-Path $CandidateTestApk).Path

if (!$Output) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Output = "D:\DevData\TestArtifacts\gap6-qa-$timestamp"
}
$Output = [IO.Path]::GetFullPath($Output)
if ($Output.Substring(0, 3) -ine 'D:\') { throw 'Targeted QA artifacts must reside on D:' }
if (Test-Path -LiteralPath $Output) { throw "Refusing existing output path: $Output" }

$drive = Get-PSDrive D
if ($drive.Free -lt 8GB) { throw 'Temporary AVD requires at least 8 GB free on D' }
New-Item -ItemType Directory -Path $Output | Out-Null

$trackedSources = @(
    'uhabits-android/src/main/java/org/isoron/uhabits/intents/IntentScheduler.kt',
    'uhabits-android/src/main/java/org/isoron/uhabits/intents/PendingIntentFactory.kt',
    'uhabits-android/src/main/java/org/isoron/uhabits/receivers/ReminderReceiver.kt',
    'uhabits-android/src/main/java/org/isoron/uhabits/activities/settings/SettingsFragment.kt',
    'uhabits-android/src/androidTest/java/org/isoron/uhabits/intents/RealDeniedExactAlarmTest.kt',
    'uhabits-core/src/commonMain/kotlin/org/isoron/uhabits/core/containers/facade/HabitOrganizationFacadeImpl.kt',
    'uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/show/timer/TimerSessionManager.kt',
    'uhabits-android/src/main/java/org/isoron/uhabits/activities/habits/show/timer/PomodoroAlarmReceiver.kt',
    'tools/qa/run-gap6-qa.ps1'
)

function Get-TrackedHashes {
    $map = [ordered]@{}
    $map['CandidateApk'] = Get-Sha256 $CandidateApk
    $map['CandidateTestApk'] = Get-Sha256 $CandidateTestApk
    foreach ($rel in $trackedSources) {
        $p = Join-Path $repo $rel
        if (Test-Path -LiteralPath $p) {
            $map[$rel] = Get-Sha256 $p
        }
    }
    return $map
}

$initialHashes = Get-TrackedHashes
$initialHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Output 'hashes-initial.json') -Encoding utf8
Write-Host "Initial Artifact Hashes (SHA-256):"
$initialHashes.GetEnumerator() | ForEach-Object { Write-Host "  $($_.Key): $($_.Value)" }

$adb = 'D:\DevData\Android\Sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw "Missing adb tool at $adb" }

$runner = 'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'org.isoron.uhabits.intents.RealDeniedExactAlarmTest'

Write-Host "Starting isolated API 35 AVD for GAP-6 native QA on port $Port..."
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

    $avd = (Adb @('shell', 'getprop', 'ro.boot.qemu.avd_name') | Out-String).Trim()
    if ($avd -notmatch '^UHabit_Temp_[a-f0-9]+$') { throw "Unexpected AVD name: $avd (refusing non-temporary AVD)" }
    Write-Host "Connected to isolated temporary AVD: $avd ($serial)"

    $apiLevel = (Adb @('shell', 'getprop', 'ro.build.version.sdk') | Out-String).Trim()
    Write-Host "Device API level: $apiLevel"

    $deviceInfo = [ordered]@{
        Serial = $serial
        AvdName = $avd
        ApiLevel = $apiLevel
        BuildFingerprint = (Adb @('shell', 'getprop', 'ro.build.fingerprint') | Out-String).Trim()
    }
    $deviceInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Output 'device-info.json') -Encoding utf8

    try {
        Write-Host "Configuring isolated device environment (airplane mode, notifications)..."
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

        # CRITICAL: Deny SCHEDULE_EXACT_ALARM via appops BEFORE launching instrumentation
        Write-Host "Denying SCHEDULE_EXACT_ALARM via appops for package org.isoron.uhabits.plus..."
        Adb @('shell', 'cmd', 'appops', 'set', 'org.isoron.uhabits.plus', 'SCHEDULE_EXACT_ALARM', 'deny') | Out-Null
        Adb @('shell', 'cmd', 'appops', 'set', 'org.isoron.uhabits.plus.test', 'SCHEDULE_EXACT_ALARM', 'deny') | Out-Null

        $appops = Adb @('shell', 'cmd', 'appops', 'get', 'org.isoron.uhabits.plus', 'SCHEDULE_EXACT_ALARM')
        Write-Host "Verified AppOps state for SCHEDULE_EXACT_ALARM: $($appops -join '; ')"
        $appops | Set-Content -LiteralPath (Join-Path $Output 'appops-state.txt') -Encoding utf8

        # Force-stop application to apply permission changes cleanly
        Adb @('shell', 'am', 'force-stop', 'org.isoron.uhabits.plus') | Out-Null

        Write-Host "Executing GAP-6 instrumentation tests: $testClass..."
        $log = Adb @('shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', $testClass,
            $runner)
        $logText = $log -join "`n"
        $logText | Set-Content -LiteralPath (Join-Path $Output "gap6-instrumentation-output.txt") -Encoding utf8

        Write-Host "Instrumentation raw output summary:"
        $log | Select-Object -Last 12 | ForEach-Object { Write-Host "  $_" }

        $passed = $logText -match 'OK \(\d+ tests?\)' -and $logText -notmatch 'FAILURES|INSTRUMENTATION_FAILED|Process crashed'
        if (!$passed) {
            Write-Error "Test execution FAILED for ${testClass}:`n$logText"
            throw "GAP-6 instrumentation test failed: $testClass"
        }
        Write-Host "PASS: $testClass"
    } finally {
        Write-Host "Collecting device diagnostics to $Output..."
        try {
            Adb @('shell', 'dumpsys', 'alarm', 'org.isoron.uhabits.plus') | Set-Content -LiteralPath (Join-Path $Output 'dumpsys-alarm.txt') -Encoding utf8
        } catch {
            Write-Warning "Failed to capture dumpsys alarm: $_"
        }
        try {
            Adb @('shell', 'logcat', '-d', '-t', '5000') | Set-Content -LiteralPath (Join-Path $Output 'logcat.txt') -Encoding utf8
        } catch {
            Write-Warning "Failed to capture logcat: $_"
        }
        Adb @('shell', 'am', 'force-stop', 'org.isoron.uhabits.plus') | Out-Null
    }
}

# Post-QA hash verification to detect drift
$finalHashes = Get-TrackedHashes
$finalHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Output 'hashes-final.json') -Encoding utf8
$drifted = @()
foreach ($key in $initialHashes.Keys) {
    if ($initialHashes[$key] -ne $finalHashes[$key]) {
        $drifted += $key
    }
}
if ($drifted.Count -gt 0) {
    throw "Source drift detected during QA run in: $($drifted -join ', '). Failing closed as directed."
}
Write-Host "Source stability verified: 0 tracked files drifted during QA."
Write-Host "GAP-6 native QA successfully finished. Evidence directory: $Output"
