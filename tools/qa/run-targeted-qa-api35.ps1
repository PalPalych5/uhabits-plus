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

    $avd = (Adb @('shell', 'getprop', 'ro.boot.qemu.avd_name') | Out-String).Trim()
    if ($avd -notmatch '^UHabit_Temp_[a-f0-9]+$') { throw "Unexpected AVD name: $avd" }
    Write-Host "Connected to isolated temporary AVD: $avd ($serial)"

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

        foreach ($tc in $testClasses) {
            Write-Host "Executing targeted instrumentation: $tc..."
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

        Write-Host "All targeted Android instrumentation QA passed successfully!"
    } finally {
        Write-Host "Collecting device diagnostics to $Output..."
        try {
            Adb @('shell', 'logcat', '-d', '-t', '5000') | Set-Content -LiteralPath (Join-Path $Output 'logcat.txt') -Encoding utf8
        } catch {
            Write-Warning "Failed to capture logcat: $_"
        }
        Adb @('shell', 'am', 'force-stop', 'org.isoron.uhabits.plus') | Out-Null
    }
}

Write-Host "Targeted Android QA successfully finished. Evidence directory: $Output"
