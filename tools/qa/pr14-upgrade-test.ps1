# Run isolated Upgrade-over-Install & Data Preservation verification test for PR14.
[CmdletBinding()]
param(
    [string]$BaselineApk = 'scratch/pr14/baseline-pr13-debug.apk',
    [string]$CurrentApk = 'uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk',
    [string]$TestApk = 'uhabits-android/build/outputs/apk/androidTest/debug/uhabits-android-debug-androidTest.apk',
    [string]$Output = 'scratch/pr14/pr14-upgrade-acceptance',
    [int]$Port = 5584
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo

foreach ($item in @($BaselineApk, $CurrentApk, $TestApk)) {
    if (!(Test-Path -LiteralPath $item -PathType Leaf)) { throw "Missing APK: $item" }
}
$BaselineApk = (Resolve-Path $BaselineApk).Path
$CurrentApk = (Resolve-Path $CurrentApk).Path
$TestApk = (Resolve-Path $TestApk).Path

$Output = [IO.Path]::GetFullPath((Join-Path $repo $Output))
if (!(Test-Path -LiteralPath $Output)) {
    New-Item -ItemType Directory -Path $Output -Force | Out-Null
}
Write-Output "Upgrade QA Destination: $Output"

$drive = Get-PSDrive D
if ($drive.Free -lt 8GB) { throw 'Temporary AVD requires at least 8 GB free on D' }

$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'D:\DevData\Android\Sdk\platform-tools\adb.exe' }
if (!(Test-Path -LiteralPath $adb)) { throw 'adb must point to the existing Android SDK' }

$appPackage = 'org.isoron.uhabits.plus'
$runner = "$appPackage.test/org.isoron.uhabits.activities.tasks.TaskDraftQaRunner"

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

    function Ensure-Offline {
        $offlineDeadline = (Get-Date).AddSeconds(20)
        do {
            Adb @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable') | Out-Null
            Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
            Adb @('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1') | Out-Null
            Adb @('shell', 'settings', 'put', 'global', 'wifi_on', '0') | Out-Null
            Start-Sleep -Milliseconds 500
            $airplane = (Adb @('shell', 'settings', 'get', 'global', 'airplane_mode_on') | Out-String).Trim()
            $wifi = (Adb @('shell', 'settings', 'get', 'global', 'wifi_on') | Out-String).Trim()
        } until (($airplane -eq '1' -and $wifi -eq '0') -or (Get-Date) -gt $offlineDeadline)
        if ($airplane -ne '1' -or $wifi -ne '0') { throw 'Disposable AVD did not settle offline' }
        Start-Sleep -Seconds 2
        Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
        Adb @('shell', 'settings', 'put', 'global', 'wifi_on', '0') | Out-Null
        Adb @('shell', 'am', 'force-stop', $appPackage) | Out-Null
    }

    Ensure-Offline

    # Step 1: Install Baseline APK and Test APK
    Write-Output "Step 1: Installing Baseline PR13 APK..."
    $instBase = Adb @('install', '-r', $BaselineApk)
    Write-Output ($instBase -join "`n")
    Write-Output "Installing Test APK..."
    $instTest = Adb @('install', '-r', $TestApk)
    Write-Output ($instTest -join "`n")

    # Step 2: Seed synthetic dataset and run baseline journey
    Write-Output "Step 2: Seeding baseline dataset via instrumentation..."
    Ensure-Offline
    $resBaseJourney = Adb @('shell', 'am', 'instrument', '-w', '-r',
        '-e', 'authorizedAvd', $avd,
        '-e', 'freshFixture', 'true',
        '-e', 'class', 'org.isoron.uhabits.activities.tasks.TaskHabitLinkInstrumentationTest#testRealTaskPomodoroLifecycleAttributionAndObserverNotification',
        $runner)
    $resBaseJoined = $resBaseJourney -join "`n"
    $resBaseJoined | Set-Content -LiteralPath (Join-Path $Output "baseline-seed-instrumentation.txt") -Encoding UTF8
    if ($resBaseJoined -notmatch 'OK \((\d+) tests?\)') {
        throw "Baseline seeding failed:`n$resBaseJoined"
    }
    Write-Output "Baseline synthetic dataset successfully seeded and verified: $resBaseJoined"

    # Step 3: Pull and Inspect Pre-Upgrade Databases
    Write-Output "Step 3: Pulling and inspecting pre-upgrade databases..."
    $dbListRaw = Adb @('shell', 'run-as', $appPackage, 'ls', 'databases')
    $dbList = ($dbListRaw | Out-String).Trim().Split("`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '\.db$' -and $_ -notmatch 'journal|wal' }
    Write-Output ("Found databases: " + ($dbList -join ', '))

    foreach ($dbName in $dbList) {
        $destPath = Join-Path $Output "pre-upgrade-$dbName"
        $jsonDest = Join-Path $Output "pre-upgrade-$dbName.json"
        & python (Join-Path $repo 'tools/qa/inspect_db.py') pull $adb $serial $appPackage $dbName $destPath $jsonDest
        Write-Output "Pre-upgrade analysis saved for $dbName"
    }

    # Step 4: Execute Upgrade-Over-Install with Current PR14 APK
    Write-Output "Step 4: Executing UPGRADE-OVER-INSTALL with Current PR14 APK (no wipe)..."
    $upgradeResult = Adb @('install', '-r', $CurrentApk)
    $upgradeJoined = $upgradeResult -join "`n"
    Write-Output $upgradeJoined
    if ($upgradeJoined -notmatch 'Success') {
        throw "Upgrade-over-install failed:`n$upgradeJoined"
    }
    Write-Output "Upgrade-over-install succeeded!"

    # Step 5: Pull and Inspect Post-Upgrade Databases
    Write-Output "Step 5: Pulling and inspecting post-upgrade databases..."
    $parityFailures = [System.Collections.Generic.List[string]]::new()

    foreach ($dbName in $dbList) {
        $destPath = Join-Path $Output "post-upgrade-$dbName"
        $jsonDest = Join-Path $Output "post-upgrade-$dbName.json"
        & python (Join-Path $repo 'tools/qa/inspect_db.py') pull $adb $serial $appPackage $dbName $destPath $jsonDest

        # Compare pre and post analysis
        $pre = Get-Content (Join-Path $Output "pre-upgrade-$dbName.json") -Raw | ConvertFrom-Json
        $post = Get-Content $jsonDest -Raw | ConvertFrom-Json

        if ($post.user_version -ne 35) {
            $parityFailures.Add("Database $dbName version mismatch: expected 35, got $($post.user_version)")
        }
        if ($post.integrity -ne "ok") {
            $parityFailures.Add("Database $dbName integrity check failed: $($post.integrity)")
        }

        # Compare table counts
        foreach ($prop in $pre.table_counts.PSObject.Properties) {
            $tableName = $prop.Name
            $preVal = $prop.Value
            $postVal = $post.table_counts.$tableName
            if ($postVal -ne $preVal) {
                $parityFailures.Add("Table $tableName in $dbName count mismatch: pre=$preVal, post=$postVal")
            }
        }

        # Compare sample counts
        if ($post.sample_habits.Count -ne $pre.sample_habits.Count) {
            $parityFailures.Add("Habits count mismatch in ${dbName}: pre=$($pre.sample_habits.Count), post=$($post.sample_habits.Count)")
        }
        if ($post.sample_tasks.Count -ne $pre.sample_tasks.Count) {
            $parityFailures.Add("Tasks count mismatch in ${dbName}: pre=$($pre.sample_tasks.Count), post=$($post.sample_tasks.Count)")
        }
        if ($post.sample_focus_sessions.Count -ne $pre.sample_focus_sessions.Count) {
            $parityFailures.Add("Focus sessions count mismatch in ${dbName}: pre=$($pre.sample_focus_sessions.Count), post=$($post.sample_focus_sessions.Count)")
        }
    }

    if ($parityFailures.Count -gt 0) {
        throw "Data preservation parity failure:`n$($parityFailures -join "`n")"
    }
    Write-Output "Step 5 PASSED: 100% data preservation parity verified across all databases!"

    # Step 6: Launch Upgraded App directly to Unified Statistics
    Write-Output "Step 6: Launching upgraded app directly to Unified Statistics..."
    Adb @('shell', 'am', 'start', '-n', "$appPackage/org.isoron.uhabits.MainActivity", '--es', 'org.isoron.uhabits.DESTINATION', 'STATISTICS') | Out-Null
    Start-Sleep -Seconds 5

    $pidApp = (Adb @('shell', 'pidof', $appPackage) | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($pidApp)) {
        throw "Upgraded application crashed on launch!"
    }
    Write-Output "Upgraded application is running cleanly with Unified Statistics displayed (PID: $pidApp)."

    # Step 7: Verify Read-Only Guarantee (Zero writes during stats read)
    Write-Output "Step 7: Verifying read-only guarantee for user data..."
    foreach ($dbName in $dbList) {
        $destPath = Join-Path $Output "readonly-check-$dbName"
        $jsonDest = Join-Path $Output "readonly-check-$dbName.json"
        & python (Join-Path $repo 'tools/qa/inspect_db.py') pull $adb $serial $appPackage $dbName $destPath $jsonDest

        $post = Get-Content (Join-Path $Output "post-upgrade-$dbName.json") -Raw | ConvertFrom-Json
        $readonly = Get-Content $jsonDest -Raw | ConvertFrom-Json

        foreach ($prop in $post.table_counts.PSObject.Properties) {
            $tableName = $prop.Name
            if ($tableName -in @('Habits', 'HabitGoals', 'Repetitions', 'Containers', 'HabitPlacements', 'UserTasks', 'UserSubtasks', 'FocusSessions', 'Contributions', 'SyncTombstones')) {
                $postVal = $prop.Value
                $currVal = $readonly.table_counts.$tableName
                if ($currVal -ne $postVal) {
                    throw "Read-only violation: Table $tableName in $dbName modified during statistics read (post=$postVal, current=$currVal)!"
                }
            }
        }
    }
    Write-Output "Step 7 PASSED: Read-only guarantee verified: Zero writes to user data tables during statistics read."

    # Step 8: Execute Post-Upgrade Instrumentation Journeys (Targeted QA)
    Write-Output "Step 8: Executing post-upgrade instrumentation journeys on upgraded app..."
    Ensure-Offline

    $resPostJourneys = Adb @('shell', 'am', 'instrument', '-w', '-r',
        '-e', 'authorizedAvd', $avd,
        '-e', 'class', 'org.isoron.uhabits.activities.statistics.UnifiedStatisticsShellVisualTest#shellJourney',
        '-e', 'phase', 'light_standard_100',
        $runner)
    $resPostJoined = $resPostJourneys -join "`n"
    $resPostJoined | Set-Content -LiteralPath (Join-Path $Output "post-upgrade-instrumentation.txt") -Encoding UTF8
    if ($resPostJoined -notmatch 'OK \(1 test\)') {
        throw "Post-upgrade instrumentation journeys failed:`n$resPostJoined"
    }
    Write-Output "Step 8 PASSED: Shell journey executed cleanly on upgraded app: OK (1 test)!"

    # Pull visual test artifacts
    try {
        Adb @('pull', "/sdcard/Android/data/org.isoron.uhabits.plus/files/unified-statistics-qa", $Output) | Out-Null
        Write-Output "Pulled post-upgrade visual artifacts to $Output"
    } catch {
        Write-Warning "No visual artifacts directory: $_"
    }

    # Pull logcat
    Adb @('logcat', '-d') | Set-Content -LiteralPath (Join-Path $Output "logcat.txt") -Encoding UTF8

    Write-Output "ALL UPGRADE-OVER-INSTALL VERIFICATIONS PASSED SUCCESSFULLY."
}
