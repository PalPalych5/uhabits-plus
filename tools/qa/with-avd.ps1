# Run a caller-supplied QA action with exclusive ownership of one AVD.
# Example: .\with-avd.ps1 -AvdName UHabit_Next_Dev_API35 -Action { param($serial) & adb -s $serial shell getprop sys.boot_completed }
[CmdletBinding()]
param(
    [string]$AvdName = 'UHabit_Next_Dev_API35',
    [int]$Port = 5580,
    [switch]$Temporary,
    [switch]$Headless,
    [ValidateRange(1, 3600)][int]$BootTimeoutSeconds = 600,
    [switch]$ListOrphans,
    [string]$Package = 'system-images;android-35;google_apis;x86_64',
    [string]$Device = 'pixel_6',
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$AvdHome = $env:ANDROID_AVD_HOME,
    [string]$TemporaryRoot = 'D:\DevData\Android\temporary',
    [string]$StateRoot = "$(if ($env:TEST_ARTIFACTS_HOME) { $env:TEST_ARTIFACTS_HOME } else { 'D:\DevData\TestArtifacts' })\avd-lifecycle",
    [string]$LeaseRoot = "$(if ($env:ANDROID_AVD_LEASE_HOME) { $env:ANDROID_AVD_LEASE_HOME } else { 'D:\DevData\Android\leases' })",
    [scriptblock]$Action
)
$ErrorActionPreference = 'Stop'
function Get-OwnerAlive($record) {
    $owner = Get-Process -Id $record.ownerPid -ErrorAction SilentlyContinue
    return $owner -and $owner.StartTime.ToUniversalTime().ToString('o') -eq $record.ownerStartedUtc
}
if ($ListOrphans) {
    if (Test-Path -LiteralPath $StateRoot) {
        Get-ChildItem -LiteralPath $StateRoot -Filter '*.json' -File | ForEach-Object {
            try {
                $record = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
                if ($record.temporary -and $record.status -ne 'cleaned' -and !(Get-OwnerAlive $record)) {
                    [pscustomobject]@{ Manifest = $_.FullName; Avd = $record.avd; Home = $record.home; Status = $record.status; Note = 'Dead owner; inspect emulator processes before cleanup' }
                }
            } catch { Write-Warning "Cannot inspect manifest $($_.FullName): $_" }
        }
    }
    return
}
if (!$Action) { throw 'Supply -Action { param($serial) ... }; no implicit device mutations are performed.' }
if (!$Sdk -or !(Test-Path -LiteralPath $Sdk -PathType Container)) { throw 'ANDROID_HOME / -Sdk must identify an existing SDK.' }
if ($Port -lt 5554 -or $Port -gt 5682 -or $Port % 2) { throw 'Use an even emulator console port between 5554 and 5682.' }
if ($AvdName -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Unsafe AVD name.' }
if (!$AvdHome) { $AvdHome = 'D:\DevData\Android\avd' }
$sdkPath = [IO.Path]::GetFullPath($Sdk)
$adb = Join-Path $sdkPath 'platform-tools\adb.exe'
$emulator = Join-Path $sdkPath 'emulator\emulator.exe'
$manager = Join-Path $sdkPath 'cmdline-tools\latest\bin\avdmanager.bat'
foreach ($tool in @($adb, $emulator, $manager)) { if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing Android tool: $tool" } }
New-Item -ItemType Directory -Force -Path $StateRoot, $LeaseRoot | Out-Null
$runId = [guid]::NewGuid().ToString('N')
if ($Temporary) {
    $AvdName = "UHabit_Temp_$runId"
    $AvdHome = Join-Path $TemporaryRoot $runId
    if ([IO.Path]::GetFullPath($AvdHome).Substring(0, 3) -ine 'D:\') { throw 'Temporary AVDs must reside on D.' }
} elseif (!(Test-Path -LiteralPath (Join-Path $AvdHome "$AvdName.ini"))) { throw "Existing persistent AVD is required: $AvdName in $AvdHome" }
$AvdHome = [IO.Path]::GetFullPath($AvdHome)
$leasePath = Join-Path $LeaseRoot "$AvdName.lease"
$lease = $null
$portLease = $null
$ownedEmulator = $null
$ownedEmulatorStartedUtc = $null
$operationFailure = $null
$manifestPath = Join-Path $StateRoot "$runId.json"
$saved = @{}
foreach ($key in @('ANDROID_AVD_HOME', 'TEMP', 'TMP')) { $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$record = [ordered]@{ runId = $runId; avd = $AvdName; home = $AvdHome; temporary = [bool]$Temporary; port = $Port; ownerPid = $PID; ownerStartedUtc = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o'); status = 'leased'; emulatorPid = $null }
function Save-Manifest { $record | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8 }
function Invoke-Adb([string[]]$Arguments) {
    if ($Arguments.Length -gt 0 -and $Arguments[0] -eq 'shell') {
        $cleanArgs = @('shell', '-n', '-T')
        for ($i = 1; $i -lt $Arguments.Length; $i++) {
            if ($Arguments[$i] -ne '-n' -and $Arguments[$i] -ne '-T') {
                $cleanArgs += $Arguments[$i]
            }
        }
        $Arguments = $cleanArgs
    }
    $result = & $adb -s "emulator-$Port" @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($result -join ' ')" }
    return $result
}
function Assert-Identity {
    $identity = Invoke-Adb @('emu', 'avd', 'name')
    if (($identity | Select-Object -First 1).ToString().Trim() -cne $AvdName) { throw 'AVD identity mismatch; refusing device operation.' }
}
function Stop-OwnedTemporaryProcesses {
    if (!$Temporary -or !$ownedEmulator -or !$ownedEmulatorStartedUtc) { throw 'Missing temporary process ownership evidence.' }
    $candidates = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -match '^(emulator|qemu-system.*|qemu-img|cmd)\.exe$' -and $_.CommandLine -match [regex]::Escape($runId)
    })
    $nodes = @()
    foreach ($candidate in $candidates) {
        $process = Get-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue
        if (!$process) { continue }
        $started = $process.StartTime.ToUniversalTime()
        $nodes += [pscustomobject]@{ Process = $process; Pid = $candidate.ProcessId; ParentPid = $candidate.ParentProcessId; StartedUtc = $started; Name = $candidate.Name; Executable = $candidate.ExecutablePath; Command = $candidate.CommandLine; Depth = -1 }
    }
    $record['cleanupProcessSnapshot'] = @($nodes | Select-Object Pid, ParentPid, StartedUtc, Name, Executable, Command)
    Save-Manifest
    # Use Get-Process timestamps throughout; CIM CreationDate truncates sub-microsecond ticks.
    foreach ($node in $nodes) {
        if ($node.Pid -eq $ownedEmulator.Id -and $node.StartedUtc -eq $ownedEmulatorStartedUtc) { $node.Depth = 0 }
    }
    do {
        $progress = $false
        foreach ($node in @($nodes | Where-Object Depth -LT 0)) {
            $parent = $nodes | Where-Object { $_.Pid -eq $node.ParentPid -and $_.Depth -ge 0 } | Select-Object -First 1
            $parentDepth = -1
            $parentStarted = $null
            $parentName = $null
            if ($parent) { $parentDepth = $parent.Depth; $parentStarted = $parent.StartedUtc; $parentName = $parent.Name }
            elseif ($node.ParentPid -eq $ownedEmulator.Id) { $parentDepth = 0; $parentStarted = $ownedEmulatorStartedUtc; $parentName = 'emulator.exe' }
            if ($parentDepth -lt 0 -or $node.StartedUtc -lt $parentStarted) { continue }
            $allowed = $false
            if ($node.Name -match '^qemu-system.*\.exe$') { $allowed = $parentName -eq 'emulator.exe' }
            elseif ($node.Name -eq 'qemu-img.exe') { $allowed = $parentName -match '^(emulator|qemu-system.*|cmd)\.exe$' }
            elseif ($node.Name -eq 'cmd.exe') {
                $allowed = $node.Executable -ieq (Join-Path $env:WINDIR 'System32\cmd.exe') -and
                    $node.Command -match [regex]::Escape((Join-Path $sdkPath 'emulator\qemu-img.exe')) -and
                    $parentName -match '^(emulator|qemu-system.*)\.exe$'
            }
            if ($allowed) { $node.Depth = $parentDepth + 1; $progress = $true }
        }
    } while ($progress)
    $unknown = @($nodes | Where-Object Depth -LT 0)
    if ($unknown.Count) { throw "Unknown temporary process ancestry: $($unknown.Pid -join ','); preserving resources; see cleanupProcessSnapshot." }
    # The entire graph is validated before stopping any converter, intermediary or parent.
    foreach ($entry in ($nodes | Sort-Object Depth -Descending)) {
        $current = Get-Process -Id $entry.Pid -ErrorAction SilentlyContinue
        if ($current -and $current.StartTime.ToUniversalTime() -ne $entry.StartedUtc) { throw 'PID reused during cleanup; preserving resources.' }
        if ($current) { Stop-Process -InputObject $current -Force -ErrorAction Stop }
    }
}
try {
    # FileShare.None excludes concurrent callers, while the file survives crashes for inspection.
    $lease = [IO.File]::Open($leasePath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $portLease = [IO.File]::Open((Join-Path $LeaseRoot "port-$Port.lease"), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    Save-Manifest
    $env:ANDROID_AVD_HOME = $AvdHome
    $runTemp = Join-Path 'D:\DevData\Temp' $runId
    $record['processTemp'] = $runTemp
    Save-Manifest
    New-Item -ItemType Directory -Force -Path $runTemp | Out-Null
    $env:TEMP = $runTemp
    $env:TMP = $runTemp
    $connections = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq $Port -or $_.LocalPort -eq ($Port + 1) })
    $matching = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -match '^(emulator|qemu-system.*|qemu-img|cmd)\.exe$' -and $_.CommandLine -match ('(?<![A-Za-z0-9_.-])' + [regex]::Escape($AvdName) + '(?![A-Za-z0-9_.-])') })
    if ($matching.Count -and !$connections.Count) { throw 'AVD is already running on another port; refusing parallel access.' }
    if ($connections.Count) {
        if ($Temporary) { throw 'Requested temporary emulator port is busy.' }
        Assert-Identity
    } else {
        if ($Temporary) {
            $free = Get-PSDrive D
            if ($free.Free -lt 8GB) { throw 'At least 8 GiB free on D is required to provision a temporary AVD.' }
            New-Item -ItemType Directory -Path $AvdHome | Out-Null
            $record.status = 'creating'; Save-Manifest
            'no' | & $manager create avd -n $AvdName -k $Package --device $Device -p (Join-Path $AvdHome "$AvdName.avd")
            if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed; inspect the retained manifest.' }
        }
        # Capture the exact process; shared persistent guests are deliberately left running.
        $launchArguments = @('-avd', $AvdName, '-port', $Port, '-no-snapshot', '-no-audio')
        if ($Headless) { $launchArguments += '-no-window' }
        $ownedEmulator = Start-Process -FilePath $emulator -ArgumentList $launchArguments -PassThru -WindowStyle Hidden
        $ownedEmulatorStartedUtc = $ownedEmulator.StartTime.ToUniversalTime()
        $record.emulatorPid = $ownedEmulator.Id
        $record['emulatorStartedUtc'] = $ownedEmulatorStartedUtc.ToString('o')
        $record.status = 'booting'; Save-Manifest
    }
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $bootAttempts = 0
    do {
        Start-Sleep -Seconds 2
        $bootAttempts++
        if ($bootAttempts -gt 5) {
            $matching = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^(emulator|qemu-system.*)\.exe$' -and $_.CommandLine -match [regex]::Escape($AvdName) })
            if ($matching.Count -eq 0) { throw "Emulator process for $AvdName exited prematurely." }
        }
        if ((Get-Date) -gt $deadline) { throw 'Emulator boot timeout.' }
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $boot = & $adb -s "emulator-$Port" shell -n -T getprop sys.boot_completed 2>$null
            $bootExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousErrorAction }
    } until ($bootExit -eq 0 -and ($boot -join '').Trim() -eq '1')
    Assert-Identity
    $record.status = 'running'; Save-Manifest
    & $Action "emulator-$Port"
    $record.status = 'completed'; Save-Manifest
} catch {
    $operationFailure = $_
    throw
} finally {
    if ($lease) {
        try {
            if ($Temporary -and $portLease) {
                $mayDelete = $true
                if ($ownedEmulator) {
                    # Never stop a reused/shared guest or an unrelated process at a recycled PID.
                    $beforeCleanup = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -match '^(emulator|qemu-system.*|qemu-img|cmd)\.exe$' -and $_.CommandLine -match [regex]::Escape($runId) })
                    if ($beforeCleanup.Count) {
                        try {
                            Assert-Identity
                            Invoke-Adb @('emu', 'kill') | Out-Null
                        } catch {
                            Write-Warning 'ADB shutdown unavailable; checking exact temporary process ancestry before stopping owned processes.'
                            Stop-OwnedTemporaryProcesses
                        }
                    }
                    $deadline = (Get-Date).AddSeconds(60)
                    do {
                        $remaining = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -match '^(emulator|qemu-system.*|qemu-img|cmd)\.exe$' -and $_.CommandLine -match [regex]::Escape($runId) })
                        if (!$remaining.Count) { break }
                        Start-Sleep -Seconds 1
                    } while ((Get-Date) -lt $deadline)
                    if ($remaining.Count) { $mayDelete = $false; throw 'Owned emulator still running; retain AVD and manifest.' }
                }
                if ($mayDelete -and (Test-Path -LiteralPath (Join-Path $AvdHome "$AvdName.ini"))) {
                    & $manager delete avd -n $AvdName
                    if ($LASTEXITCODE -ne 0) { throw 'Temporary AVD deletion failed; retained manifest identifies the guest.' }
                }
                if (Test-Path -LiteralPath $AvdHome) {
                    $leftovers = @(Get-ChildItem -LiteralPath $AvdHome -Force)
                    if ($leftovers.Count) { throw "Temporary AVD home still contains files: $AvdHome; retain for inspection." }
                    # Keep the empty run directory as a durable ownership marker.
                }
                $record.status = 'cleaned'; Save-Manifest
            }
        } catch {
            $record.status = 'cleanup-required'; Save-Manifest
            Write-Warning "Temporary cleanup incomplete: $_. Manifest: $manifestPath"
            if (!$operationFailure) { $operationFailure = $_ }
        } finally { if ($portLease) { $portLease.Dispose() }; $lease.Dispose() }
    }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    if ($runTemp -and (Test-Path -LiteralPath $runTemp)) { Write-Verbose "Process temporary files retained at $runTemp; inspect before disposal." }
}
if ($operationFailure) { throw $operationFailure }
