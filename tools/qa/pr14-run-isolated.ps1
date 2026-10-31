[CmdletBinding()]
param(
    [string]$Output = '',
    [int]$Port = 5584,
    [string[]]$Phases = @('light_standard_100', 'dark_standard_100', 'amoled_standard_100',
        'light_narrow320_100', 'dark_narrow320_100', 'amoled_narrow320_100',
        'light_standard_200', 'dark_standard_200', 'amoled_standard_200',
        'light_narrow320_200', 'dark_narrow320_200', 'amoled_narrow320_200'),
    [Alias('TestClass')]
    [string]$Class = 'org.isoron.uhabits.activities.statistics.UnifiedStatisticsShellVisualTest#shellJourney,org.isoron.uhabits.activities.statistics.UnifiedStatisticsTaskFocusVisualTest#taskFocusJourney,org.isoron.uhabits.activities.statistics.UnifiedStatisticsHabitOverviewVisualTest#habitOverviewJourney'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo
$apk = Join-Path $repo 'uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk'
$testApk = Join-Path $repo 'uhabits-android/build/outputs/apk/androidTest/debug/uhabits-android-debug-androidTest.apk'
foreach ($file in @($apk, $testApk)) { if (!(Test-Path -LiteralPath $file)) { throw "Missing APK: $file" } }
if (!$Output) { $Output = Join-Path $repo ('scratch/pr14/android-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$Output = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $Output) { throw "Refusing existing output: $Output" }
if ($Output.Substring(0,3) -ine 'D:\') { throw 'QA artifacts must be on D' }
if ((Get-PSDrive D).Free -lt 8GB) { throw 'Temporary AVD requires 8GB free on D' }
New-Item -ItemType Directory -Path $Output | Out-Null
$adb = 'D:/DevData/Android/Sdk/platform-tools/adb.exe'
$env:ANDROID_HOME = 'D:/DevData/Android/Sdk'
$results = [System.Collections.Generic.List[object]]::new()
& (Join-Path $PSScriptRoot 'with-avd.ps1') -Temporary -Headless -Port $Port -Sdk $env:ANDROID_HOME -Action {
    param($serial)
    function Adb([string[]]$Arguments) {
        if ($Arguments[0] -eq 'shell') { $Arguments = @('shell', '-n', '-T') + $Arguments[1..($Arguments.Length-1)] }
        $oldPreference = $ErrorActionPreference
        try { $ErrorActionPreference = 'Continue'; $result = $null | & $adb -s $serial @Arguments 2>&1 }
        finally { $ErrorActionPreference = $oldPreference }
        if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($result -join ' ')" }
        return $result
    }
    $avd = (Adb @('shell','getprop','ro.boot.qemu.avd_name') | Out-String).Trim()
    if ($avd -notmatch '^UHabit_Temp_[a-f0-9]+$') { throw 'Refusing non-disposable AVD' }
    try {
        Adb @('shell','cmd','connectivity','airplane-mode','enable') | Out-Null
        Adb @('shell','svc','wifi','disable') | Out-Null
        Adb @('shell','settings','put','global','airplane_mode_on','1') | Out-Null
        Adb @('shell','settings','put','global','wifi_on','0') | Out-Null
        Adb @('shell','settings','put','global','wifi_scan_always_enabled','0') | Out-Null
        Adb @('shell','cmd','alarm','set-timezone','Asia/Novosibirsk') | Out-Null
        Write-Host "Installing APKs on $avd..."
        Adb @('install',$apk) | Out-Null
        Adb @('install',$testApk) | Out-Null
        Adb @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
        Adb @('shell','wm','dismiss-keyguard') | Out-Null
        Adb @('shell','pm','grant','org.isoron.uhabits.plus','android.permission.POST_NOTIFICATIONS') | Out-Null
        foreach ($phase in $Phases) {
            Write-Host "Configuring phase $phase..."
            if ($phase -notmatch '^(light|dark|amoled)_(standard|narrow320)_(100|200)$') { throw "Invalid phase: $phase" }
            $scale = if ($phase.EndsWith('_200')) { '2.0' } else { '1.0' }
            $width = if ($phase.Contains('narrow320')) { '320' } else { '0' }
            Adb @('shell','wm','size','reset') | Out-Null
            Adb @('shell','wm','density','reset') | Out-Null
            if ($width -eq '320') {
                Adb @('shell','wm','density','480') | Out-Null
                Adb @('shell','wm','size','960x1920') | Out-Null
            }
            Adb @('shell','settings','put','system','font_scale',$scale) | Out-Null
            $offlineDeadline = (Get-Date).AddSeconds(20)
            do {
                Adb @('shell','cmd','connectivity','airplane-mode','enable') | Out-Null
                Adb @('shell','svc','wifi','disable') | Out-Null
                Adb @('shell','settings','put','global','airplane_mode_on','1') | Out-Null
                Adb @('shell','settings','put','global','wifi_on','0') | Out-Null
                Start-Sleep -Milliseconds 500
                $airplane = (Adb @('shell','settings','get','global','airplane_mode_on') | Out-String).Trim()
                $wifi = (Adb @('shell','settings','get','global','wifi_on') | Out-String).Trim()
            } until (($airplane -eq '1' -and $wifi -eq '0') -or (Get-Date) -gt $offlineDeadline)
            if ($airplane -ne '1' -or $wifi -ne '0') { throw 'Disposable AVD did not settle offline' }
            Start-Sleep -Seconds 3
            Adb @('shell','svc','wifi','disable') | Out-Null
            Adb @('shell','settings','put','global','wifi_on','0') | Out-Null
            Adb @('shell','am','force-stop','org.isoron.uhabits.plus') | Out-Null
            Write-Host "Running instrumentation for $phase with $Class..."
            $log = Adb @('shell','am','instrument','-w','-r','-e','authorizedAvd',$avd,
                '-e','freshFixture','true','-e','phase',$phase,'-e','expectedFontScale',$scale,
                '-e','expectedWidthDp',$width,'-e','class',
                $Class,
                'org.isoron.uhabits.plus.test/org.isoron.uhabits.activities.tasks.TaskDraftQaRunner')
            $log | Set-Content -LiteralPath (Join-Path $Output "$phase-instrumentation.txt") -Encoding utf8
            Write-Host "Instrumentation completed. Pulling artifacts..."
            foreach ($dir in @('unified-statistics-qa', 'unified-statistics-task-focus-qa', 'unified-statistics-habit-overview-qa')) {
                try { Adb @('pull', "/sdcard/Android/data/org.isoron.uhabits.plus/files/$dir", $Output) | Out-Null }
                catch { Write-Warning "No $dir directory yet: $_" }
            }
            $passed = ($log -join "`n") -match 'OK \(\d+ tests?\)' -and ($log -join "`n") -notmatch 'FAILURES|INSTRUMENTATION_FAILED|Process crashed'
            $hashes = @{}
            $sha256 = [System.Security.Cryptography.SHA256]::Create()
            Get-ChildItem -Path $Output -File -Recurse | ForEach-Object {
                if ($_.Name -ne 'manifest.json' -and $_.Name -ne 'logcat.txt') {
                    $rel = $_.FullName.Substring($Output.Length).TrimStart('\', '/')
                    $stream = [System.IO.File]::OpenRead($_.FullName)
                    try {
                        $bytes = $sha256.ComputeHash($stream)
                        $hashes[$rel] = (-join ($bytes | ForEach-Object { $_.ToString("X2") }))
                    } finally {
                        $stream.Close()
                    }
                }
            }
            $sha256.Dispose()
            $results.Add([pscustomobject]@{
                phase = $phase;
                passed = $passed;
                avd = $avd;
                fontScale = $scale;
                requestedWidthDp = $width;
                testClass = $Class;
                fileHashes = $hashes
            })
            $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Output 'manifest.json') -Encoding utf8
            if (!$passed) { throw "Instrumentation failed: $phase. See $Output" }
        }
    } finally {
        Adb @('shell','logcat','-d','-t','2000') | Set-Content -LiteralPath (Join-Path $Output 'logcat.txt') -Encoding utf8
        Adb @('shell','am','force-stop','org.isoron.uhabits.plus') | Out-Null
        Adb @('shell','wm','size','reset') | Out-Null
        Adb @('shell','wm','density','reset') | Out-Null
        Adb @('shell','settings','put','system','font_scale','1.0') | Out-Null
    }
}
Write-Output "PR14.4 isolated evidence: $Output"
