# Preparation only: run after the coordinator grants sole device ownership.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CandidateApk,
    [Parameter(Mandatory)][string]$CandidateTestApk,
    [string]$BaselineApk = 'scratch/pr10/baseline-v33.apk',
    [string]$BaselineTestApk = 'scratch/pr10/baseline-v33-test.apk',
    [string]$Output = 'scratch/pr10/runtime',
    [int]$Port = 5580,
    [switch]$LightOnly
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo
$requiredApks = if ($LightOnly) { @($CandidateApk, $CandidateTestApk) } else { @($CandidateApk, $CandidateTestApk, $BaselineApk, $BaselineTestApk) }
foreach ($item in $requiredApks) {
    if (!(Test-Path -LiteralPath $item -PathType Leaf)) { throw "Missing APK: $item" }
}
$CandidateApk = (Resolve-Path $CandidateApk).Path
$CandidateTestApk = (Resolve-Path $CandidateTestApk).Path
if (!$LightOnly) {
    $BaselineApk = (Resolve-Path $BaselineApk).Path
    $BaselineTestApk = (Resolve-Path $BaselineTestApk).Path
}
$Output = [IO.Path]::GetFullPath((Join-Path $repo $Output))
if (!$Output.StartsWith((Join-Path $repo 'scratch/pr10'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must stay in scratch/pr10' }
if (Test-Path -LiteralPath $Output) { throw 'Use a new output directory; never overwrite previous evidence' }
$drive = Get-PSDrive D
if ($drive.Free -lt 8GB) { throw 'Temporary AVD requires at least 8 GB free on D' }
New-Item -ItemType Directory -Path $Output | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw 'ANDROID_HOME must point to the existing Android SDK' }
$appPackage = 'org.isoron.uhabits.plus'
$runner = "$appPackage.test/org.isoron.uhabits.activities.tasks.TaskDraftQaRunner"
$class = 'org.isoron.uhabits.activities.tasks.TaskMatrixVisualTest'
$database = 'uhabits-container-experimental.db'

& (Join-Path $PSScriptRoot 'with-avd.ps1') -Temporary -Headless -Port $Port -Action {
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
        $r = if ($TargetRunner) { $TargetRunner } else {
            $line = (Adb @('shell', 'pm', 'list', 'instrumentation') | Where-Object { $_ -match [regex]::Escape($appPackage) } | Select-Object -First 1)
            if ($line -match 'instrumentation:([^ ]+)') { $matches[1] } else { $runner }
        }
        $result = Adb (@('shell', 'am', 'instrument', '-w', '-r', '-e', 'authorizedAvd', $avd, '-e', 'class', $Selection, '-e', 'phase', $Phase) + $Extra + @($r))
        $result | Set-Content -LiteralPath (Join-Path $Output "$Phase-instrumentation.txt") -Encoding UTF8
        $joined = $result -join "`n"
        if ($joined -notmatch 'OK \(1 test\)' -or $joined -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed: $Phase" }
    }
    function Snapshot([string]$Name) {
        Adb @('shell', 'am', 'force-stop', $appPackage) | Out-Null
        $directory = Join-Path $Output $Name
        New-Item -ItemType Directory -Path $directory | Out-Null
        foreach ($suffix in @('', '-wal', '-shm')) {
            $remote = "databases/$database$suffix"
            $null | & $adb -s $serial shell -n -T "run-as $appPackage test -f $remote"
            if ($LASTEXITCODE -ne 0) { if (!$suffix) { throw 'Missing synthetic database' }; continue }
            $start = New-Object Diagnostics.ProcessStartInfo
            $start.FileName = $adb
            $start.Arguments = "-s $serial exec-out run-as $appPackage cat $remote"
            $start.UseShellExecute = $false
            $start.CreateNoWindow = $true
            $start.RedirectStandardOutput = $true
            $start.RedirectStandardError = $true
            $process = [Diagnostics.Process]::Start($start)
            $stream = [IO.File]::Create((Join-Path $directory "$database$suffix"))
            try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
            $diagnostic = $process.StandardError.ReadToEnd(); $process.WaitForExit()
            if ($process.ExitCode -ne 0) { throw "Database copy failed: $diagnostic" }
            $process.Dispose()
        }
        return (Join-Path $directory $database)
    }
    try {
        Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
        Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'wifi_on', '0') | Out-Null
        Adb @('shell', 'cmd', 'alarm', 'set-timezone', 'Asia/Novosibirsk') | Out-Null
        Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
        if ($LightOnly) {
            Adb @('install', $CandidateApk) | Out-Null
            Adb @('install', $CandidateTestApk) | Out-Null
            Instrument "$class#matrixJourneys" 'light' @('-e', 'freshFixture', 'true')
        } else {
            Adb @('install', $BaselineApk) | Out-Null
            Adb @('install', $BaselineTestApk) | Out-Null
            Instrument 'org.isoron.uhabits.activities.tasks.TaskListVisualTest#verifyPr8QuickActionsMultiSelectionAndUndo' 'before' @('-e', 'freshFixture', 'true')
            $before = Snapshot 'before-v33'
            Adb @('shell', 'mkdir', '-p', "/sdcard/Android/data/$appPackage/files/pr10/baseline") | Out-Null
            foreach ($suffix in @('', '-wal', '-shm')) {
                $local = "$before$suffix"
                if (Test-Path -LiteralPath $local) { Adb @('push', $local, "/sdcard/Android/data/$appPackage/files/pr10/baseline/$database$suffix") | Out-Null }
            }
            # In-place install: deliberately no uninstall, clear-data or freshFixture from here.
            Adb @('install', '-r', $CandidateApk) | Out-Null
            Adb @('install', '-r', $CandidateTestApk) | Out-Null
            Instrument "$class#verifyInPlaceUpgrade" 'upgrade'
            $after = Snapshot 'after-v34'
            # verifyInPlaceUpgrade checks both physical schema versions and every original
            # UserTasks/TaskTags/TaskTagLinks/Containers value using native SQLite cursors.
            foreach ($theme in @('light', 'dark', 'amoled')) { Instrument "$class#matrixJourneys" $theme }
            Adb @('shell', 'wm', 'size', '900x2000') | Out-Null
            Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.3') | Out-Null
            Instrument "$class#matrixJourneys" 'narrow-font13'
            Adb @('shell', 'wm', 'size', 'reset') | Out-Null
            Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.0') | Out-Null
            Adb @('shell', 'am', 'force-stop', $appPackage) | Out-Null
            Instrument "$class#verifyAfterProcessRestart" 'restart'
        }
    } finally {
        # Keep evidence even if instrumentation fails; wrapper owns emulator/AVD cleanup.
        $prev = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null | & $adb -s $serial pull "/sdcard/Android/data/$appPackage/files/pr10" $Output 2>&1 | Out-File (Join-Path $Output 'pull.txt') -Encoding UTF8
            $null | & $adb -s $serial logcat -d -t 2500 2>&1 | Out-File (Join-Path $Output 'logcat.txt') -Encoding UTF8
            $null | & $adb -s $serial shell -n -T wm size reset 2>$null | Out-Null
            $null | & $adb -s $serial shell -n -T settings put system font_scale 1.0 2>$null | Out-Null
        } finally {
            $ErrorActionPreference = $prev
        }
    }
}
if ($LASTEXITCODE -ne 0) { throw 'Temporary AVD campaign failed' }
Write-Output "PR10 runtime artifacts: $Output"
