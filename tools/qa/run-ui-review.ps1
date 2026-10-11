param(
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Output = "$PSScriptRoot\..\..\scratch\ui-review\runs\$(Get-Date -Format yyyyMMdd-HHmmss)",
    [switch]$SkipBuild,
    [switch]$MeasurePerformance,
    [string]$VerifiedFunctionalRun
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
$avdName = 'UHabit_UI_Review_Disposable_20261011'
$serial = 'emulator-5580'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
if (Test-Path $Output) { throw 'Output already exists; choose a fresh run directory to preserve previous evidence.' }
$avdHome = Join-Path $repo 'scratch\ui-review\avd-home-api35'
function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $result = & $adb -s $serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $Arguments" }
    return $result
}
function ProbeAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; & $adb -s $serial @Arguments 2>$null }
    finally { $ErrorActionPreference = $previousErrorAction }
}
if (!(Test-Path (Join-Path $avdHome "$avdName.ini"))) {
    New-Item -ItemType Directory -Force $avdHome | Out-Null
    $env:ANDROID_AVD_HOME = $avdHome
    'no' | & (Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat') create avd -n $avdName -k 'system-images;android-35;google_apis;x86_64' --device pixel_6
    if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed' }
}
$identity = ProbeAdb @('emu','avd','name')
if ($LASTEXITCODE -ne 0) {
    $env:ANDROID_AVD_HOME = $avdHome
    Start-Process (Join-Path $Sdk 'emulator\emulator.exe') -WindowStyle Hidden -ArgumentList @('-avd',$avdName,'-port','5580','-no-snapshot','-no-audio','-no-window','-gpu','swiftshader')
}
$deadline = (Get-Date).AddMinutes(15)
do {
    Start-Sleep -Seconds 5
    $boot = ProbeAdb @('shell','getprop','sys.boot_completed')
    if ((Get-Date) -gt $deadline) { throw 'Disposable AVD boot timed out' }
} until ($boot -contains '1')
$identity = Adb @('emu','avd','name')
if (($identity | Select-Object -First 1).Trim() -ne $avdName) { throw "Wrong AVD; refusing mutations: $identity" }
if ((Adb @('shell','getprop','ro.build.version.sdk')).Trim() -ne '35') { throw 'Primary capture requires the dedicated stable API 35 AVD; refusing to alter another image.' }
# Only the verified dedicated AVD is altered. Airplane mode + both radios disabled.
Adb @('shell','cmd','connectivity','airplane-mode','enable') | Out-Null
Adb @('shell','svc','wifi','disable') | Out-Null
& $adb -s $serial shell svc data disable 2>$null | Out-Null # A Wi-Fi-only AVD has no phone service.
Adb @('shell','cmd','alarm','set-timezone','Asia/Novosibirsk') | Out-Null
Adb @('shell','svc','power','stayon','true') | Out-Null
Adb @('shell','settings','put','system','screen_off_timeout','1800000') | Out-Null
Adb @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
Adb @('shell','wm','dismiss-keyguard') | Out-Null
Adb @('shell','settings','put','system','system_locales','ru-RU') | Out-Null
if (((Adb @('shell','am','get-config')) -join ' ') -notmatch 'ru-rRU') {
    Adb @('root') | Out-Null
    Adb @('wait-for-device') | Out-Null
    if (((Adb @('emu','avd','name')) | Select-Object -First 1).Trim() -ne $avdName) { throw 'AVD identity changed after root' }
    Adb @('shell','setprop','persist.sys.locale','ru-RU') | Out-Null
    Adb @('reboot') | Out-Null
    Adb @('wait-for-device') | Out-Null
    $deadline = (Get-Date).AddMinutes(10)
    do {
        Start-Sleep -Seconds 5
        $boot = ProbeAdb @('shell','getprop','sys.boot_completed')
        if ((Get-Date) -gt $deadline) { throw 'Locale reboot timed out' }
    } until ($boot -contains '1')
    if (((Adb @('emu','avd','name')) | Select-Object -First 1).Trim() -ne $avdName) { throw 'AVD identity changed after locale reboot' }
    if (((Adb @('shell','am','get-config')) -join ' ') -notmatch 'ru-rRU') { throw 'Russian locale was not applied' }
}
if (!$SkipBuild) {
    Push-Location $repo
    try { & .\gradlew.bat :uhabits-android:assembleDebug :uhabits-android:assembleDebugAndroidTest --console=plain --quiet; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } }
    finally { Pop-Location }
}
$apkSha256 = (Get-FileHash (Join-Path $repo 'uhabits-android\build\outputs\apk\debug\uhabits-android-debug.apk') -Algorithm SHA256).Hash
Adb @('install','-r', (Join-Path $repo 'uhabits-android\build\outputs\apk\debug\uhabits-android-debug.apk')) | Out-Null
Adb @('install','-r', (Join-Path $repo 'uhabits-android\build\outputs\apk\androidTest\debug\uhabits-android-debug-androidTest.apk')) | Out-Null
Adb @('shell','pm','grant','org.isoron.uhabits.plus','android.permission.POST_NOTIFICATIONS') | Out-Null
foreach ($animation in @('window_animation_scale','transition_animation_scale','animator_duration_scale')) { Adb @('shell','settings','put','global',$animation,'0') | Out-Null }
# This directory contains only tool-produced screenshots/diagnostics in the verified QA AVD.
Adb @('shell','rm','-rf','/sdcard/Android/data/org.isoron.uhabits.plus/files/ui-review') | Out-Null
New-Item -ItemType Directory -Force $Output | Out-Null
if ($VerifiedFunctionalRun) {
    $verified = Get-Content (Join-Path $VerifiedFunctionalRun 'capture-manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $currentHash = (Get-FileHash (Join-Path $repo 'uhabits-android\build\outputs\apk\debug\uhabits-android-debug.apk') -Algorithm SHA256).Hash
    if ($verified.apkSha256 -ne $currentHash) { throw 'Functional evidence belongs to a different app APK.' }
}
foreach ($method in @('cancelDisposableFocus','prepareAndSave','verifyAfterRestart')) {
    if ($VerifiedFunctionalRun) {
        $functional = Get-Content (Join-Path $VerifiedFunctionalRun "functional-$method.txt") -Encoding utf8
    } else {
    $functional = Adb @('shell','am','instrument','-w','-e','class',"org.isoron.uhabits.qa.UiReviewFunctionalTest#$method",'-e','disposableAvd',$avdName,'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner')
    }
    $functional | Set-Content (Join-Path $Output "functional-$method.txt") -Encoding utf8
    if (($functional -join "`n") -notmatch 'OK \(1 test\)' -or
        ($functional -join "`n") -match 'Process crashed|FAILURES!!!|INSTRUMENTATION_FAILED') { throw "Functional check failed: $method" }
    if (!$VerifiedFunctionalRun -and $method -eq 'prepareAndSave') { Adb @('shell','am','force-stop','org.isoron.uhabits.plus') | Out-Null }
}
if ($MeasurePerformance) {
    $performance = Adb @('shell','am','instrument','-w','-e','class','org.isoron.uhabits.qa.HabitTapPerformanceAndroidTest','-e','performanceVariant','after','-e','disposableAvd',$avdName,'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner')
    $performance | Set-Content (Join-Path $Output 'performance-instrumentation.txt') -Encoding utf8
    if (($performance -join "`n") -notmatch 'OK \(1 test\)') { throw 'Performance instrumentation failed' }
}
$run = Adb @('shell','am','instrument','-w','-e','class','org.isoron.uhabits.qa.UiReviewCaptureTest','-e','disposableAvd',$avdName,'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner')
$run | Set-Content (Join-Path $Output 'instrumentation.txt') -Encoding utf8
$primaryRunnerReportedSuccess = (($run -join "`n") -match 'OK \(1 test\)' -and ($run -join "`n") -notmatch 'Process crashed|FAILURES!!!|INSTRUMENTATION_FAILED')
$primaryCaptureCompleted = $primaryRunnerReportedSuccess
$partialManifestPath = Join-Path $Output 'capture-manifest-primary.json'
$partialPull = Adb @('pull','/sdcard/Android/data/org.isoron.uhabits.plus/files/ui-review/capture-manifest.json',$partialManifestPath)
if (Test-Path $partialManifestPath) {
    $partialManifest = Get-Content $partialManifestPath -Raw -Encoding utf8 | ConvertFrom-Json
    $validCaptures = @($partialManifest.captures | Where-Object { $_.status -eq 'captured' -and $_.image })
    $variantNames = @($validCaptures | Select-Object -ExpandProperty theme -Unique)
    $requiredRoutes = @('habits|main-list','habits|details','tasks|today','tasks|detail-notes-subtasks','containers|navigation-tree','focus|preparation','focus|pomodoro-preparation','statistics|global-focus-containers','settings|appearance')
    $capturedRoutes = @($validCaptures | ForEach-Object { "$($_.screen)|$($_.route)" })
    $requiredRoutesPresent = @($requiredRoutes | Where-Object { $capturedRoutes -contains $_ }).Count -eq $requiredRoutes.Count
    $primaryCaptureCompleted = $validCaptures.Count -ge 110 -and
        (@('light','dark','large-font' | Where-Object { $variantNames -contains $_ }).Count -eq 3) -and
        $requiredRoutesPresent
    if (!$primaryRunnerReportedSuccess -and $primaryCaptureCompleted) {
        Write-Warning "Instrumentation exited after storing $($validCaptures.Count) route screenshots; persisted manifest covers the required routes and all variants. Continue with a separate verification test."
    }
}
$captureCompleted = $primaryCaptureCompleted
if ($primaryCaptureCompleted) {
    $dialogs = Adb @('shell','am','instrument','-w','-e','class','org.isoron.uhabits.qa.UiReviewDialogCaptureTest','-e','disposableAvd',$avdName,'org.isoron.uhabits.plus.test/androidx.test.runner.AndroidJUnitRunner')
    $dialogs | Set-Content (Join-Path $Output 'dialog-instrumentation.txt') -Encoding utf8
    $captureCompleted = (($dialogs -join "`n") -match 'OK \(1 test\)' -and ($dialogs -join "`n") -notmatch 'Process crashed|FAILURES!!!')
}
if (!$captureCompleted) { Write-Warning 'Traversal incomplete; packaging surviving actual captures and diagnostics.' }
Adb @('pull','/sdcard/Android/data/org.isoron.uhabits.plus/files/ui-review/.',$Output) | Out-Null
$manifest = Get-Content (Join-Path $Output 'capture-manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
$manifest | Add-Member -NotePropertyName traversalCompleted -NotePropertyValue $captureCompleted -Force
$manifest | Add-Member -NotePropertyName primaryTraversalCompleted -NotePropertyValue $primaryCaptureCompleted -Force
$manifest | Add-Member -NotePropertyName primaryRunnerReportedSuccess -NotePropertyValue $primaryRunnerReportedSuccess -Force
$manifest | Add-Member -NotePropertyName apkSha256 -NotePropertyValue $apkSha256 -Force
$manifest | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $Output 'capture-manifest.json') -Encoding utf8
$index = @('# UHabit Next: actual Android screenshots', '', "AVD: $avdName. Seed: $($manifest.seed). Reference date: $($manifest.referenceDate).", '')
foreach ($capture in $manifest.captures) {
    if ($capture.status -eq 'captured') { $index += "- [$($capture.theme) / $($capture.screen) / $($capture.route)]($($capture.image))" }
    else { $index += "- FAILED: $($capture.theme) / $($capture.screen) / $($capture.route): $($capture.error). [Diagnostic screenshot]($($capture.diagnosticImage))" }
}
$index | Set-Content (Join-Path $Output 'index.md') -Encoding utf8
$failed = @('# Automatically unavailable screens', '')
if (!$captureCompleted) { $failed += '- Primary traversal or supplementary dialog checks did not complete; inspect instrumentation.txt and dialog-instrumentation.txt. Unvisited screens are not treated as successes.' }
$failed += '- Backup creation warning: requires a configured backup folder; automated run does not perform backup/restore.'
$failed += '- Empty global statistics: fixture deliberately contains historical facts; no production-like facts are erased during traversal.'
foreach ($capture in $manifest.captures | Where-Object status -eq failed) { $failed += "- $($capture.theme) / $($capture.screen) / $($capture.route): $($capture.error)" }
$failed | Set-Content (Join-Path $Output 'uncaptured-screens.md') -Encoding utf8
if (!(Test-Path (Join-Path $Output 'ux-issues.md'))) { '# UX issues: pending visual inspection' | Set-Content (Join-Path $Output 'ux-issues.md') }
'# Performance for this run: inspect performance-after.json if --MeasurePerformance was used. A before/after comparison requires the separately controlled baseline APK; no comparison is fabricated by this runner.' | Set-Content (Join-Path $Output 'performance-report.md')
$zip = Join-Path (Split-Path $Output) 'UHabit_Next_UI_Review.zip'
Compress-Archive -Path "$Output\*" -DestinationPath $zip -Force
Write-Output $zip
