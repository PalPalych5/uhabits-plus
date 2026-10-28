param(
    [Parameter(Mandatory=$true)][string]$Output,
    [int]$Samples = 5,
    [string]$Sdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$qaAdb = Join-Path $Sdk 'platform-tools\adb.exe'
$serial = 'emulator-5580'
$avd = 'UHabit_UI_Review_Disposable_20261011'
if ((& $qaAdb -s $serial emu avd name | Select-Object -First 1).Trim() -ne $avd) { throw 'Wrong disposable AVD' }
if (Test-Path -LiteralPath $Output) { throw 'Preserve old measurements; choose a fresh output folder' }
New-Item -ItemType Directory -Path $Output | Out-Null
function ReadTrace {
    $lines = & $qaAdb -s $serial logcat -d -v brief -s 'UHabitStartup:I' '*:S'
    @($lines | ForEach-Object { if ($_ -match '(\{.*\})') { $Matches[1] | ConvertFrom-Json } })
}
function WaitFrame($stage) {
    $deadline = (Get-Date).AddSeconds(20)
    do {
        $events = ReadTrace
        if ($events.stage -contains $stage) { return $events }
        if ((Get-Date) -gt $deadline) { throw "No frame: $stage" }
        Start-Sleep -Milliseconds 100
    } while ($true)
}
for ($i = 0; $i -lt $Samples; $i++) {
    & $qaAdb -s $serial shell am force-stop org.isoron.uhabits.plus | Out-Null
    & $qaAdb -s $serial logcat -c
    & $qaAdb -s $serial shell am start -W -n org.isoron.uhabits.plus/org.isoron.uhabits.activities.main.MainActivity --es main.destination HABITS |
        Set-Content (Join-Path $Output "cold-$i-am-start.txt")
    $events = WaitFrame 'list.frame.commit'
    $events | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $Output "cold-$i.json")
}
for ($i = 0; $i -lt $Samples; $i++) {
    & $qaAdb -s $serial logcat -c
    & $qaAdb -s $serial shell am start -W -f 0x10008000 -n org.isoron.uhabits.plus/org.isoron.uhabits.activities.main.MainActivity --es main.destination HABITS |
        Set-Content (Join-Path $Output "warm-$i-am-start.txt")
    $events = WaitFrame 'list.frame.commit'
    if ($events.stage -contains 'application.create.begin') { throw 'Warm launch unexpectedly recreated the process' }
    $events | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $Output "warm-$i.json")
    & $qaAdb -s $serial logcat -c
    & $qaAdb -s $serial shell am start -W -f 0x20000000 -n org.isoron.uhabits.plus/org.isoron.uhabits.activities.main.MainActivity --es main.destination TASKS | Out-Null
    WaitFrame 'workspace.tasks.draw' | Out-Null
    & $qaAdb -s $serial logcat -c
    & $qaAdb -s $serial shell am start -W -f 0x20000000 -n org.isoron.uhabits.plus/org.isoron.uhabits.activities.main.MainActivity --es main.destination HABITS | Out-Null
    $events = WaitFrame 'list.frame.commit'
    if ($events.stage -contains 'application.create.begin' -or $events.stage -contains 'repository.load.begin') { throw 'Navigation performed a cold reload' }
    $events | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $Output "navigation-$i.json")
}
Get-FileHash 'uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk' |
    ConvertTo-Json | Set-Content (Join-Path $Output 'apk-hash.json')
