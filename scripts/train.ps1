# AimAssist's neural network: self-play training in the background.
#   train                start training (until stoptrain)
#   train -Until 07:00   start, stop by itself at 07:00
#   train lite           super light: 1 thread working 10% of the time, idle priority,
#                        128 MB - run it in the background while you do other things
#   train status         is it running, the last log lines
#   train stop           stop it (= stoptrain)
#   train install        train every night (scheduled task "Beast Client nightly training")
#   train uninstall      remove the nightly task
#   train nightly        what the nightly task runs (22:30): checks the PC is free, trains
#                        until 07:30, stops early if Minecraft starts
param(
	[ValidateSet("start", "lite", "stop", "status", "install", "uninstall", "nightly")]
	[string]$Action = "start",
	[string]$Until = "",
	[int]$Threads = 0
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
$Neural = Join-Path $env:APPDATA ".minecraft\wurst\neural"
$Dir = Join-Path $Neural "trainer"
$Jar = Join-Path $Dir "beast-trainer.jar"
$Log = Join-Path $Dir "train.log"
$StopFile = Join-Path $Dir "stop"
$PidFile = Join-Path $Dir "pid"
$TaskName = "Beast Client nightly training"
$NightlyStart = "22:30"
$NightlyUntil = "07:30"

function Get-Trainer {
	if (-not (Test-Path $PidFile)) { return $null }
	$id = [int](Get-Content $PidFile -Raw)
	$p = Get-Process -Id $id -ErrorAction SilentlyContinue
	if ($p -and $p.ProcessName -like "java*") { return $p }
	return $null
}

function Find-Java {
	if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javaw.exe")) {
		return "$env:JAVA_HOME\bin\javaw.exe"
	}
	$java = Get-Command javaw.exe -ErrorAction SilentlyContinue
	if ($java) { return $java.Source }
	throw "No Java found (needs Java 25: set JAVA_HOME or put it on PATH)."
}

# Minecraft itself (any launcher), not the trainer or Gradle. The game's window
# is "Minecraft 26.1.2" / "Minecraft* 26.1.2 - Singleplayer" (the launcher's is
# "Minecraft Launcher"); command lines aren't always readable.
function Test-MinecraftRunning {
	if (Get-Process | Where-Object { $_.MainWindowTitle -match "^Minecraft\*? \d" }) { return $true }
	$game = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
		Where-Object {
			$_.CommandLine -match "net\.minecraft\.client\.main\.Main|KnotClient|net\.fabricmc\.loader\.impl\.launch|minecraft\.launcher\.brand|prismlauncher|multimc" -and
			$_.CommandLine -notmatch "beast-trainer"
		}
	return [bool]$game
}

function Update-Jar {
	New-Item -ItemType Directory -Force $Dir | Out-Null
	Push-Location $Repo
	try {
		& .\gradlew.bat neuralTrainerJar -q -PnoAnticheat | Out-Host
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

function Start-Trainer([string]$until, [string]$priority, [switch]$lite) {
	$running = Get-Trainer
	if ($running) {
		Write-Host "Already training (process $($running.Id))."
		return $running
	}
	Update-Jar
	if (Test-Path $StopFile) { Remove-Item $StopFile -Force }
	if ($lite) {
		# one thread, asleep 90% of the time, small generations, tiny heap
		$n = if ($Threads -gt 0) { $Threads } else { 1 }
		$argList = @("-Xms32m", "-Xmx128m", "-XX:+UseSerialGC",
			"-Dnn.threads=$n", "-Dnn.duty=0.1", "-Dnn.pairs=16", "-Dnn.games=12")
	} else {
		$n = if ($Threads -gt 0) { $Threads } else { [Environment]::ProcessorCount }
		$argList = @("-Xms256m", "-Xmx1g", "-XX:+UseParallelGC", "-Dnn.threads=$n")
	}
	$argList += @("-cp", "`"$Jar`"", "net.wurstclient.hacks.aimassist.neural.NeuralTrainer",
		"forever", "`"$Neural`"")
	if ($until) { $argList += $until }
	$p = Start-Process -FilePath (Find-Java) -ArgumentList $argList -WorkingDirectory $Dir -PassThru
	$p.PriorityClass = $priority
	# wait for it to take the lock (or find another trainer has it)
	for ($i = 0; $i -lt 40 -and -not $p.HasExited -and -not (Test-Path $PidFile); $i++) {
		Start-Sleep -Milliseconds 250
	}
	if ($p.HasExited) { throw "The trainer quit right away - see $Log" }
	return $p
}

function Stop-Trainer {
	$p = Get-Trainer
	if (-not $p) { Write-Host "Not training."; return }
	New-Item -ItemType File -Force $StopFile | Out-Null
	Write-Host "Stopping (finishing the current round check)..."
	if (-not $p.WaitForExit(60000)) {
		Write-Host "Didn't stop in 60 s - killing it."
		Stop-Process -Id $p.Id -Force
	}
	Write-Host "Stopped."
	Show-Log 3
}

function Show-Log([int]$lines) {
	if (Test-Path $Log) { Get-Content $Log -Tail $lines }
}

switch ($Action) {
	"start" {
		$p = Start-Trainer $Until "BelowNormal"
		Write-Host "Training in the background (process $($p.Id), $([Environment]::ProcessorCount) threads, below-normal priority)."
		Write-Host "  stoptrain     stop it"
		Write-Host "  train status  progress (log: $Log)"
	}
	"lite" {
		$p = Start-Trainer $Until "Idle" -lite
		Write-Host "Light training in the background (process $($p.Id): 1 thread, ~10% of it, idle priority, 128 MB)."
		Write-Host "  stoptrain     stop it"
		Write-Host "  train status  progress (log: $Log)"
	}
	"stop" { Stop-Trainer }
	"status" {
		$p = Get-Trainer
		if ($p) {
			$cpu = [math]::Round($p.TotalProcessorTime.TotalHours, 1)
			Write-Host "Training (process $($p.Id), running since $($p.StartTime), $cpu CPU hours)."
		} else { Write-Host "Not training." }
		$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
		if ($task) {
			$info = $task | Get-ScheduledTaskInfo
			Write-Host "Nightly task: $($task.State), next run $($info.NextRunTime), last run $($info.LastRunTime)."
		} else { Write-Host "Nightly task: not installed (train install)." }
		Show-Log 12
	}
	"install" {
		$ps = (Get-Command powershell.exe).Source
		$a = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`" nightly" -WorkingDirectory $Repo
		$t = New-ScheduledTaskTrigger -Daily -At $NightlyStart
		$s = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd `
			-ExecutionTimeLimit (New-TimeSpan -Hours 9 -Minutes 30) -MultipleInstances IgnoreNew -Priority 7
		Register-ScheduledTask -TaskName $TaskName -Action $a -Trigger $t -Settings $s `
			-Description "Trains Beast Client's AimAssist neural network against itself from $NightlyStart to $NightlyUntil when Minecraft isn't running and the PC is idle." -Force | Out-Null
		Write-Host "Nightly training installed: every day at $NightlyStart until $NightlyUntil (if the PC is free)."
	}
	"uninstall" {
		Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
		Write-Host "Nightly training removed."
	}
	"nightly" {
		New-Item -ItemType Directory -Force $Dir | Out-Null
		$nightLog = Join-Path $Dir "nightly.log"
		function Note([string]$m) { Add-Content $nightLog "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $m" }
		if (Get-Trainer) { Note "already training"; break }
		if (Test-MinecraftRunning) { Note "skipped: Minecraft is running"; break }
		$os = Get-CimInstance Win32_OperatingSystem
		$freeGb = [math]::Round($os.FreePhysicalMemory / 1MB, 1)
		if ($freeGb -lt 2) { Note "skipped: only $freeGb GB of RAM free"; break }
		$load = @(1..5 | ForEach-Object {
			(Get-CimInstance Win32_Processor | Measure-Object LoadPercentage -Average).Average
			Start-Sleep -Seconds 2
		}) | Measure-Object -Average
		if ($load.Average -gt 30) { Note "skipped: CPU $([math]::Round($load.Average))% busy"; break }
		$battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue
		if ($battery -and ($battery | Where-Object BatteryStatus -eq 1)) { Note "skipped: on battery"; break }

		$p = Start-Trainer $NightlyUntil "Idle"
		Note "training (process $($p.Id), $freeGb GB free, CPU $([math]::Round($load.Average))%)"
		while (-not $p.HasExited) {
			Start-Sleep -Seconds 30
			if (Test-MinecraftRunning) {
				Note "Minecraft started: stopping"
				New-Item -ItemType File -Force $StopFile | Out-Null
				if (-not $p.WaitForExit(60000)) { Stop-Process -Id $p.Id -Force }
			}
		}
		Note "trainer finished"
	}
}
