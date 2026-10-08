param(
    [string]$Serial = 'emulator-5556',
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$OutputDirectory = "$PSScriptRoot\run"
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Only a disposable emulator is permitted.' }
$qaAvd = & $Adb -s $Serial emu avd name
if ($LASTEXITCODE -ne 0 -or $qaAvd[0].Trim() -ne 'UHabit_PR23B_Compare') {
    throw 'Wrong AVD: requires UHabit_PR23B_Compare.'
}
$qaAirplane = & $Adb -s $Serial shell settings get global airplane_mode_on
$qaWifi = & $Adb -s $Serial shell settings get global wifi_on
if ($qaAirplane.Trim() -ne '1' -or $qaWifi.Trim() -ne '0') { throw 'Disable airplane/Wi-Fi network access before QA.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$qaRoot = Split-Path $PSScriptRoot -Parent
$qaApks = @(
    "$qaRoot\uhabits-android\build\outputs\apk\debug\uhabits-android-debug.apk",
    "$qaRoot\uhabits-android\build\outputs\apk\androidTest\debug\uhabits-android-debug-androidTest.apk"
)
$qaApks | Get-FileHash -Algorithm SHA256 | ConvertTo-Json | Set-Content "$OutputDirectory\apk-hashes.json"
foreach ($qaApk in $qaApks) {
    & $Adb -s $Serial install -r $qaApk
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $qaApk" }
}
$qaDeviceOutput = '/sdcard/Android/media/org.isoron.uhabits.plus/compare-qa'
$qaResults = & $Adb -s $Serial shell am instrument -w -r -e class 'org.isoron.uhabits.activities.statistics.StatisticsEffectiveAndroidTest,org.isoron.uhabits.database.AndroidDatabaseTest' -e additionalTestOutputDir $qaDeviceOutput 'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner'
$qaExit = $LASTEXITCODE
$qaResults | Set-Content "$OutputDirectory\native-results.txt"
$qaResults | Write-Output
if ($qaExit -ne 0 -or ($qaResults -join "`n") -notmatch 'OK \(9 tests\)') {
    throw 'Instrumentation did not prove all nine tests passed; adb exit code alone is insufficient.'
}
& $Adb -s $Serial pull $qaDeviceOutput "$OutputDirectory\screenshots"
if ($LASTEXITCODE -ne 0) { throw 'Screenshot evidence pull failed.' }
# No generic connectedAndroidTest, account login, canonical DB replacement, or restoration.
# The tests use separate experimental database filenames and preference namespaces.
