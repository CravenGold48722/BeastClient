# AimAssist's neural network: self-play training in the background.
#   train                start training (until stoptrain), idle priority
#                        (-Priority BelowNormal|Normal|AboveNormal if wanted; the
#                        nightly and morning runs are idle too)
#   train -Until 07:00   start, stop by itself at 07:00
#   train lite           super light: 1 thread working 10% of the time, idle priority,
#                        128 MB - run it in the background while you do other things
#   train status         is it running, the last log lines
#   train stop           stop it (= stoptrain)
#   train install        train every night and morning (scheduled tasks "Beast Client nightly
#                        training" 23:00-07:00 and "Beast Client morning training" 08:00-14:30,
#                        which starts even if the PC is busy; both run with Minecraft open)
#                        + "Beast Client training watchdog" every 90 min (train watchdog)
#   train uninstall      remove the tasks
#   train watchdog       the right training running? full 23:00-07:00 (if the PC is free)
#                        and 08:00-14:30, lite otherwise -
#                        starts or switches it if not;
#                        leaves it alone after "train stop" until the next scheduled run
#   (when training ends, however it ends, scripts/ship-net.ps1 ships the champion:
#    build, commit, push, install - log: trainer\ship.log)
#   train nightly        what the tasks run: checks the PC is free, trains until 07:00
#                        (or -Until), Minecraft open or not
#   train continue       lite until 5 min before the next scheduled run (what follows a
#                        scheduled run, however it ends - training goes on 24/7; user,
#                        2026-10-31). "train stop" by you ends that too.
#   train hold -WhilePid N   pause (suspend) the trainer until process N ends - every
#                        gametest client does this for itself (TrainerHold; -PkeepTraining
#                        doesn't); nothing it learned is lost
param(
	[ValidateSet("start", "lite", "stop", "status", "install", "uninstall", "nightly", "continue", "watchdog", "hold")]
	[string]$Action = "start",
	[string]$Until = "",
	[int]$Threads = 0,
	# stop (and ship) as soon as a new champion is promoted
	[switch]$UntilBeaten,
	# start's priority: Idle unless told otherwise (anything you run goes
	# first)
	[ValidateSet("Idle", "BelowNormal", "Normal", "AboveNormal")]
	[string]$Priority = "Idle",
	# a scheduled run (the tasks pass it): lite takes over when it ends
	[switch]$Scheduled,
	# hold: the process (a test client) the trainer waits for
	[int]$WhilePid = 0
)
# the scheduled runs (nightly, morning) too: Idle (user, 2026-10-30). With
# nothing else running, Idle gets every core anyway; BelowNormal made the PC
# sluggish whenever something else wanted the CPU (the test suite didn't
# finish in 50 min next to it)
$ScheduledPriority = "Idle"

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
$Neural = Join-Path $env:APPDATA ".minecraft\wurst\neural"
$Dir = Join-Path $Neural "trainer"
$Jar = Join-Path $Dir "beast-trainer.jar"
$Log = Join-Path $Dir "train.log"
$ErrFile = Join-Path $Dir "train.err"
$StopFile = Join-Path $Dir "stop"
$PidFile = Join-Path $Dir "pid"
$TaskName = "Beast Client nightly training"
$NightlyStart = "23:00"
$NightlyUntil = "07:00"
# a break 07:00-08:00, then the morning run (user, 2026-10-27)
$MorningTask = "Beast Client morning training"
$MorningStart = "08:00"
$MorningUntil = "14:30"
# left by a scheduled run: ship-net.ps1 starts lite after it ("train continue")
$ThenLite = Join-Path $Dir "then-lite"
# every 90 min: is the right training running? (user, 2026-11-01: the morning
# run didn't start after the clock change and nothing trained for an hour)
$WatchdogTask = "Beast Client training watchdog"
# left by "train stop": the watchdog starts nothing until the next start
$Paused = Join-Path $Dir "paused"
# left by "train hold": the PID of the test client the trainer is suspended for
$HeldBy = Join-Path $Dir "held-by"

# lite runs until 5 min before the next scheduled start, or "" if one is about
# to start anyway (the tasks skip while anything trains)
function Get-LiteUntil {
	$t = (Get-Date).TimeOfDay
	if ($t -lt [TimeSpan]"07:50") { return "07:55" }
	if ($t -lt [TimeSpan]"08:00") { return "" }
	if ($t -lt [TimeSpan]"22:50") { return "22:55" }
	if ($t -lt [TimeSpan]"23:00") { return "" }
	return "07:55"
}

function Start-LiteUntilNext {
	$u = Get-LiteUntil
	if (-not $u) { return $null }
	return Start-Trainer $u "Idle" -lite
}

function Get-Trainer {
	if (-not (Test-Path $PidFile)) { return $null }
	$id = [int](Get-Content $PidFile -Raw)
	$p = Get-Process -Id $id -ErrorAction SilentlyContinue
	if ($p -and $p.ProcessName -like "java*") { return $p }
	return $null
}

function Note([string]$m) {
	Add-Content (Join-Path $Dir "nightly.log") "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $m"
}

function Find-Java {
	if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javaw.exe")) {
		return "$env:JAVA_HOME\bin\javaw.exe"
	}
	$java = Get-Command javaw.exe -ErrorAction SilentlyContinue
	if ($java) { return $java.Source }
	throw "No Java found (needs Java 25: set JAVA_HOME or put it on PATH)."
}

function Update-Jar([switch]$noDaemon) {
	New-Item -ItemType Directory -Force $Dir | Out-Null
	Push-Location $Repo
	try {
		# lite: no Gradle daemon left behind (~0.5-1 GB idle for 3 h) for a
		# build that happens a few times a day
		$daemon = if ($noDaemon) { "--no-daemon" } else { "--daemon" }
		& .\gradlew.bat neuralTrainerJar -q -PnoAnticheat $daemon | Out-Host
		$ok = $LASTEXITCODE -eq 0
	} finally { Pop-Location }
	$built = Join-Path $Repo "build\neural\beast-trainer.jar"
	if ($ok -and (Test-Path $built)) {
		Copy-Item $built $Jar -Force
	} elseif (Test-Path $Jar) {
		Write-Host "Build failed - using the trainer from last time."
	} else {
		throw "Couldn't build the trainer."
	}
}

# Idle priority only lets everything else go first; Windows' efficiency mode
# (EcoQoS: Task Manager's leaf, and what Windows may put low-priority
# background processes into) also clocks the CPU down for it - measurably
# slower training. This opts the trainer out of that for good.
function Disable-EcoQos($proc) {
	if (-not ("TrainerQos" -as [type])) {
		Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class TrainerQos {
	[StructLayout(LayoutKind.Sequential)]
	struct PowerThrottling { public uint Version, ControlMask, StateMask; }
	[DllImport("kernel32.dll", SetLastError = true)]
	static extern bool SetProcessInformation(IntPtr process, int infoClass,
		ref PowerThrottling info, uint size);
	// ProcessPowerThrottling = 4; control EXECUTION_SPEED, state off = never throttled
	public static bool FullSpeed(IntPtr process) {
		var t = new PowerThrottling { Version = 1, ControlMask = 1, StateMask = 0 };
		return SetProcessInformation(process, 4, ref t,
			(uint)Marshal.SizeOf(typeof(PowerThrottling)));
	}
}
'@
	}
	if (-not [TrainerQos]::FullSpeed($proc.Handle)) {
		Write-Warning "couldn't opt the trainer out of efficiency mode"
	}
}

function Start-Trainer([string]$until, [string]$priority, [switch]$lite) {
	if (Test-Path $Paused) { Remove-Item $Paused -Force }
	$running = Get-Trainer
	if ($running) {
		Write-Host "Already training (process $($running.Id))."
		return $running
	}
	Update-Jar -noDaemon:$lite
	if (Test-Path $StopFile) { Remove-Item $StopFile -Force }
	if ($lite) {
		# one thread, asleep 90% of the time, small generations, tiny heap
		$n = if ($Threads -gt 0) { $Threads } else { 1 }
		# (2 JIT compiler threads, not ~4: they only work while it warms up;
		# a smaller code cache - lite's speed and fights stay the same)
		$argList = @("-Xms32m", "-Xmx128m", "-XX:+UseSerialGC",
			"-XX:CICompilerCount=2", "-XX:ReservedCodeCacheSize=64m",
			"-Dnn.threads=$n", "-Dnn.duty=0.1", "-Dnn.pairs=16", "-Dnn.games=12")
	} else {
		$n = if ($Threads -gt 0) { $Threads } else { [Environment]::ProcessorCount }
		# 1024 variants a generation (512 mirrored pairs), 12 fights each
		$argList = @("-Xms256m", "-Xmx1g", "-XX:+UseParallelGC", "-Dnn.threads=$n",
			"-Dnn.pairs=512", "-Dnn.games=12")
	}
	if ($UntilBeaten) { $argList += "-Dnn.untilBeaten=true" }
	# the SIMD forward pass (VectorKernel); without it the trainer is ~2x slower
	$argList += @("--add-modules", "jdk.incubator.vector", "-cp", "`"$Jar`"", "net.wurstclient.hacks.aimassist.neural.NeuralTrainer",
		"forever", "`"$Neural`"")
	if ($until) { $argList += $until }
	# javaw has no console: a crash before the log is open lands in train.err
	$p = Start-Process -FilePath (Find-Java) -ArgumentList $argList -WorkingDirectory $Dir -PassThru `
		-RedirectStandardError $ErrFile
	$p.PriorityClass = $priority
	Disable-EcoQos $p
	# wait for it to take the lock (or find another trainer has it)
	for ($i = 0; $i -lt 40 -and -not $p.HasExited -and -not (Test-Path $PidFile); $i++) {
		Start-Sleep -Milliseconds 250
	}
	if ($p.HasExited) {
		Show-Log 5
		if ((Test-Path $ErrFile) -and (Get-Item $ErrFile).Length -gt 0) { Get-Content $ErrFile -Tail 15 }
		throw "The trainer quit right away - see $Log and $ErrFile"
	}
	# however it ends (stoptrain, -Until, a crash, a kill), ship-net.ps1 ships
	# the champion right after; a reboot is covered at the next logon
	New-Item -ItemType File -Force (Join-Path $Dir "ship-pending") | Out-Null
	Install-ShipAtLogon
	Start-Process powershell.exe -WindowStyle Hidden -ArgumentList "-NoProfile",
		"-ExecutionPolicy", "Bypass", "-File", "`"$PSScriptRoot\ship-net.ps1`"",
		"-WaitPid", $p.Id | Out-Null
	return $p
}

# Startup folder entry: ships what a reboot interrupted (no admin rights needed)
function Install-ShipAtLogon {
	$startup = [Environment]::GetFolderPath("Startup")
	$cmd = Join-Path $startup "BeastShipNet.cmd"
	$line = "@start `"`" /min powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\ship-net.ps1`""
	if (-not (Test-Path $cmd) -or (Get-Content $cmd -Raw).Trim() -ne $line) {
		Set-Content -Path $cmd -Value $line -Encoding ASCII
	}
}

function Set-TrainerSuspended($proc, [bool]$suspend) {
	if (-not ("TrainerHold" -as [type])) {
		Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class TrainerHold {
	[DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr process);
	[DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr process);
}
'@
	}
	if ($suspend) { [void][TrainerHold]::NtSuspendProcess($proc.Handle) }
	else { [void][TrainerHold]::NtResumeProcess($proc.Handle) }
}

# a hold whose watcher died (killed with the test): resume the trainer
function Resume-OrphanedHold {
	if (-not (Test-Path $HeldBy)) { return }
	$holder = [int](Get-Content $HeldBy -Raw)
	if (Get-Process -Id $holder -ErrorAction SilentlyContinue) { return }
	$p = Get-Trainer
	if ($p) { Set-TrainerSuspended $p $false; Note "resumed: the test run that paused it is gone" }
	Remove-Item $HeldBy -Force
}

function Stop-Trainer {
	$p = Get-Trainer
	if (-not $p) { Write-Host "Not training."; return }
	# paused for a test run: it has to run to stop
	if (Test-Path $HeldBy) { Set-TrainerSuspended $p $false; Remove-Item $HeldBy -Force }
	New-Item -ItemType File -Force $StopFile | Out-Null
	Write-Host "Stopping (checking what it learned since the last round and installing it)..."
	# (a promotion is confirmed on 16,000 more fights first: ~2 min more)
	if (-not $p.WaitForExit(360000)) {
		Write-Host "Didn't stop in 6 min - killing it (the last champion stays installed)."
		Stop-Process -Id $p.Id -Force
	}
	Write-Host "Stopped. AimAssist takes the installed champion the next time it's switched on."
	Show-Log 4
}

function Show-Log([int]$lines) {
	if (Test-Path $Log) { Get-Content $Log -Tail $lines }
}

switch ($Action) {
	"start" {
		# idle by default: anything else you run goes first; efficiency mode
		# stays off (the morning task passes -Priority BelowNormal)
		$p = Start-Trainer $Until $Priority
		if ($Scheduled) { New-Item -ItemType File -Force $ThenLite | Out-Null }
		Write-Host "Training in the background (process $($p.Id), $([Environment]::ProcessorCount) threads, $($Priority.ToLower()) priority, never in efficiency mode)."
		Write-Host "  stoptrain     stop it"
		Write-Host "  train status  progress (log: $Log)"
	}
	"lite" {
		$p = Start-Trainer $Until "Idle" -lite
		Write-Host "Light training in the background (process $($p.Id): 1 thread, ~10% of it, idle priority, 128 MB)."
		Write-Host "  stoptrain     stop it"
		Write-Host "  train status  progress (log: $Log)"
	}
	"stop" {
		# stopped by you: no lite after it
		if (Test-Path $ThenLite) { Remove-Item $ThenLite -Force }
		New-Item -ItemType Directory -Force $Dir | Out-Null
		New-Item -ItemType File -Force $Paused | Out-Null
		Stop-Trainer
	}
	"hold" {
		# a test client's own PID (TrainerHold): next to a full trainer the
		# laptop's 4 cores and iGPU share one power budget - the client took
		# 392 s to start (2026-11-03). Suspended, not stopped: nothing lost.
		Resume-OrphanedHold
		$p = Get-Trainer
		$w = if ($WhilePid) { Get-Process -Id $WhilePid -ErrorAction SilentlyContinue }
		if (-not $p -or -not $w -or (Test-Path $HeldBy)) { break }
		Set-Content $HeldBy $WhilePid
		Set-TrainerSuspended $p $true
		Note "paused for a test run (process $WhilePid)"
		try { $w.WaitForExit() }
		finally {
			Set-TrainerSuspended $p $false
			Remove-Item $HeldBy -Force -ErrorAction SilentlyContinue
			Note "resumed after the test run"
		}
	}
	"watchdog" {
		Resume-OrphanedHold
		if (Test-Path $Paused) { break }
		New-Item -ItemType Directory -Force $Dir | Out-Null
		$nightLog = Join-Path $Dir "nightly.log"
		function Note([string]$m) { Add-Content $nightLog "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  watchdog: $m" }
		$t = (Get-Date).TimeOfDay
		$night = $t -ge [TimeSpan]$NightlyStart -or $t -lt [TimeSpan]$NightlyUntil
		$morning = $t -ge [TimeSpan]$MorningStart -and $t -lt [TimeSpan]$MorningUntil
		$wantFull = $night -or $morning
		$p = Get-Trainer
		if ($p) {
			$cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)").CommandLine
			$lite = $cmd -match "nn\.duty="
			# lite where lite belongs; full always (also a full run started
			# by hand outside the windows)
			if ($lite -and -not $wantFull) { break }
			if (-not $lite) { break }
			Note "lite during a scheduled run: stopping it for the full run"
			Stop-Trainer | Out-Null
		}
		# the full runs in their own process - this one ends now
		$runArgs = $null
		if ($wantFull -and $night) {
			# with the nightly's own checks (RAM, battery)
			Note "nothing training at night: starting the nightly run"
			$runArgs = @("nightly", "-Until", $NightlyUntil)
		} elseif ($wantFull) {
			Note "nothing training in the morning run: starting it"
			$runArgs = @("start", "-Until", $MorningUntil, "-Priority", $ScheduledPriority, "-Scheduled")
		}
		if ($runArgs) {
			Start-Process powershell.exe -WindowStyle Hidden -ArgumentList (@("-NoProfile",
				"-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"") + $runArgs) | Out-Null
		} else {
			$p = Start-LiteUntilNext
			if ($p) { Note "nothing training: lite (process $($p.Id))" }
		}
	}
	"continue" {
		$p = Start-LiteUntilNext
		if ($p) { Write-Host "Lite training until the next scheduled run (process $($p.Id))." }
		else { Write-Host "A scheduled run starts in a few minutes - no lite in between." }
	}
	"status" {
		$p = Get-Trainer
		if ($p) {
			$cpu = [math]::Round($p.TotalProcessorTime.TotalHours, 1)
			Write-Host "Training (process $($p.Id), running since $($p.StartTime), $cpu CPU hours)."
			if (Test-Path $HeldBy) { Write-Host "  paused while a test client runs (process $(Get-Content $HeldBy -Raw))" }
		} else { Write-Host "Not training." }
		foreach ($name in $TaskName, $MorningTask, $WatchdogTask) {
			$task = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
			if ($task) {
				$info = $task | Get-ScheduledTaskInfo
				Write-Host "${name}: $($task.State), next run $($info.NextRunTime), last run $($info.LastRunTime)."
			} else { Write-Host "${name}: not installed (train install)." }
		}
		Show-Log 12
	}
	"install" {
		$ps = (Get-Command powershell.exe).Source
		foreach ($run in @(
				@{ Name = $TaskName; Mode = "nightly"; Start = $NightlyStart; Until = $NightlyUntil },
				# the morning run starts no matter what (user, 2026-10-27)
				@{ Name = $MorningTask; Mode = "start"; Start = $MorningStart; Until = $MorningUntil })) {
			# (nightly picks its priority itself; start needs to be told - and
			# that it's a scheduled run, with lite after it)
			$extra = if ($run.Mode -eq "start") { " -Priority $ScheduledPriority -Scheduled" } else { "" }
			$a = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`" $($run.Mode) -Until $($run.Until)$extra" -WorkingDirectory $Repo
			$t = New-ScheduledTaskTrigger -Daily -At $run.Start
			# local wall-clock time: with a UTC offset in it (what -At writes) both
			# runs moved an hour early when daylight saving time ended (2026-11-01)
			$t.StartBoundary = (Get-Date $run.Start).ToString("yyyy-MM-ddTHH:mm:ss")
			$s = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd `
				-ExecutionTimeLimit (New-TimeSpan -Hours 9) -MultipleInstances IgnoreNew -Priority 7
			Register-ScheduledTask -TaskName $run.Name -Action $a -Trigger $t -Settings $s `
				-Description "Trains Beast Client's AimAssist neural network against itself from $($run.Start) to $($run.Until)$(if ($run.Mode -eq "nightly") { " when the PC isn't busy" })." -Force | Out-Null
			Write-Host "Training installed: every day at $($run.Start) until $($run.Until)$(if ($run.Mode -eq "nightly") { " (if the PC is free)" })."
		}
		$a = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`" watchdog" -WorkingDirectory $Repo
		$t = New-ScheduledTaskTrigger -Once -At "00:07" -RepetitionInterval (New-TimeSpan -Minutes 90)
		$t.StartBoundary = (Get-Date "00:07").ToString("yyyy-MM-ddTHH:mm:ss")
		$s = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd `
			-ExecutionTimeLimit (New-TimeSpan -Minutes 20) -MultipleInstances IgnoreNew -Priority 7
		Register-ScheduledTask -TaskName $WatchdogTask -Action $a -Trigger $t -Settings $s `
			-Description "Every 90 min: starts Beast Client's AimAssist training if the right one isn't running (full 23:00-07:00 and 08:00-14:30, lite otherwise)." -Force | Out-Null
		Write-Host "Watchdog installed: checks every 90 min."
	}
	"uninstall" {
		Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
		Unregister-ScheduledTask -TaskName $MorningTask -Confirm:$false -ErrorAction SilentlyContinue
		Unregister-ScheduledTask -TaskName $WatchdogTask -Confirm:$false -ErrorAction SilentlyContinue
		Write-Host "Nightly and morning training removed."
	}
	"nightly" {
		New-Item -ItemType Directory -Force $Dir | Out-Null
		# not free for the full run: lite instead (training goes on 24/7)
		function Skip([string]$why) {
			Note "skipped: $why - lite instead"
			Start-LiteUntilNext | Out-Null
		}
		if (Get-Trainer) { Note "already training"; break }
		# trains next to Minecraft too (user, 2026-11-01) - idle priority
		# gives the game the CPU first
		$os = Get-CimInstance Win32_OperatingSystem
		$freeGb = [math]::Round($os.FreePhysicalMemory / 1MB, 1)
		if ($freeGb -lt 2) { Skip "only $freeGb GB of RAM free"; break }
		# no CPU check (user, 2026-11-03): the last run's ship build made it
		# skip to lite at 23:00 - idle priority lets everything else go first
		$battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue
		if ($battery -and ($battery | Where-Object BatteryStatus -eq 1)) { Skip "on battery"; break }

		$p = Start-Trainer $(if ($Until) { $Until } else { $NightlyUntil }) $ScheduledPriority
		# lite takes over when it ends (ship-net.ps1, after shipping)
		New-Item -ItemType File -Force $ThenLite | Out-Null
		Note "training (process $($p.Id), $freeGb GB free)"
		$p.WaitForExit()
		Note "trainer finished"
	}
}
