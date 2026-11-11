# Ships the trainer's champion: puts it in the jar as the shipped net, builds
# (with every test) and installs the jar in .minecraft\mods. No git.
# Runs by itself whenever training ends, however it ends:
#   - train.ps1 starts "ship-net -WaitPid <trainer>" next to every trainer, so
#     stoptrain, -Until, a crash or a killed process all ship right after;
#   - a reboot (Windows Update) kills both, so the Startup folder's
#     BeastShipNet.cmd runs it at the next logon.
# It only ships when trainer\ship-pending exists (written at every training
# start) and no trainer is running. Log: trainer\ship.log.
param(
	[int]$WaitPid = 0
)

# not "Stop": PowerShell 5.1 turns a native program's stderr (gradle, java)
# into errors, which killed the first ship; exit codes are checked instead
$ErrorActionPreference = "Continue"
$Repo = Split-Path -Parent $PSScriptRoot
$Neural = Join-Path $env:APPDATA ".minecraft\wurst\neural"
$Dir = Join-Path $Neural "trainer"
$Pending = Join-Path $Dir "ship-pending"
# an exported champion whose build failed: the next ship builds it even
# though export then finds nothing new
$Unbuilt = Join-Path $Dir "ship-unbuilt"
$ShipLog = Join-Path $Dir "ship.log"
$PidFile = Join-Path $Dir "pid"
$Asset = Join-Path $Repo "src\main\resources\assets\wurst\neural\aimassist_pvp.bin"
$Mods = Join-Path $env:APPDATA ".minecraft\mods"

function Note([string]$m) {
	New-Item -ItemType Directory -Force $Dir | Out-Null
	Add-Content $ShipLog "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $m"
}

function Trainer-Running {
	if (-not (Test-Path $PidFile)) { return $false }
	$p = Get-Process -Id ([int](Get-Content $PidFile -Raw)) -ErrorAction SilentlyContinue
	return [bool]($p -and $p.ProcessName -like "java*")
}

# same test as train.ps1: Minecraft holds the installed jar open
function Test-MinecraftRunning {
	if (Get-Process | Where-Object { $_.MainWindowTitle -match "^Minecraft\*? \d" }) { return $true }
	$game = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
		Where-Object {
			$_.CommandLine -match "net\.minecraft\.client\.main\.Main|KnotClient|net\.fabricmc\.loader\.impl\.launch|minecraft\.launcher\.brand|prismlauncher|multimc" -and
			$_.CommandLine -notmatch "beast-trainer"
		}
	return [bool]$game
}

function Find-Java {
	if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return "$env:JAVA_HOME\bin\java.exe" }
	$java = Get-Command java.exe -ErrorAction SilentlyContinue
	if ($java) { return $java.Source }
	throw "No Java found (needs Java 25: set JAVA_HOME or put it on PATH)."
}

if ($WaitPid -gt 0) {
	$p = Get-Process -Id $WaitPid -ErrorAction SilentlyContinue
	if ($p) { $p.WaitForExit() }
}

# a scheduled run ended (its marker): lite takes over until the next one -
# training goes on 24/7 (user, 2026-10-31). Not after "train stop" by the
# user (that removes the marker).
$ThenLite = Join-Path $Dir "then-lite"
$script:thenLite = Test-Path $ThenLite
if ($script:thenLite) { Remove-Item $ThenLite -Force }
function Continue-Lite {
	if (-not $script:thenLite) { return }
	$script:thenLite = $false
	Note "lite until the next scheduled run"
	# its own process, not "& ... *>> log": the trainer it starts inherited
	# that output pipe, so the call only returned when lite ended - the new
	# jar waited 8 h to be installed (2026-11-03)
	$p = Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @(
		"-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSScriptRoot\train.ps1`"", "continue")
	$p.WaitForExit()
}

try {
if (-not (Test-Path $Pending)) { exit 0 }
if (Trainer-Running) { exit 0 }

# one ship at a time (the watcher and the logon run can meet)
$lockFile = Join-Path $Dir "ship.lock"
try { $lock = [IO.File]::Open($lockFile, "OpenOrCreate", "ReadWrite", "None") }
catch { exit 0 }
try {
	if (-not (Test-Path $Pending)) { exit 0 }

	trap { Note "  ERROR: $_"; exit 1 }
	Note "training ended - shipping"
	# a fresh trainer jar, so the export step is always there
	Push-Location $Repo
	try { & .\gradlew.bat neuralTrainerJar -q -PnoAnticheat *>> $ShipLog } finally { Pop-Location }
	$Jar = Join-Path $Repo "build\neural\beast-trainer.jar"
	$out = & (Find-Java) --add-modules jdk.incubator.vector -cp $Jar `
		net.wurstclient.hacks.aimassist.neural.NeuralTrainer export $Neural $Asset 2>&1
	$code = $LASTEXITCODE
	Note "  export: $($out -join ' ')"
	if ($code -eq 2 -and -not (Test-Path $Unbuilt)) { Remove-Item $Pending -Force; Note "  nothing new to ship"; exit 0 }
	if ($code -ne 0 -and $code -ne 2) { Note "  export FAILED (exit $code)"; exit 1 }

	Push-Location $Repo
	try {
		& .\gradlew.bat spotlessApply build -PnoAnticheat -q *>> $ShipLog
		if ($LASTEXITCODE -ne 0) {
			Note "  build FAILED (tests in build\reports\tests\test\index.html) - not committed, not installed"
			New-Item $Unbuilt -ItemType File -Force | Out-Null
			Remove-Item $Pending -Force
			exit 1
		}

		$built = Get-ChildItem "build\libs\Beast-Client-v*.jar" |
			Where-Object { $_.Name -notmatch "sources|dev" } |
			Sort-Object { [version](($_.Name -replace '^Beast-Client-v([\d.]+).*', '$1')) } |
			Select-Object -Last 1
		# (lite goes on meanwhile: the jar only waits for the game to close)
		Continue-Lite
		while (Test-MinecraftRunning) { Start-Sleep -Seconds 60 }
		Get-ChildItem $Mods -Filter "Beast-Client-*.jar" | Remove-Item -Force
		Move-Item $built.FullName $Mods
		Note "  installed $($built.Name)"
		Remove-Item $Unbuilt -Force -ErrorAction SilentlyContinue
		Remove-Item $Pending -Force
	} finally { Pop-Location }
} finally { $lock.Close() }
} finally {
	# however the ship went (nothing new, failed, installed)
	Continue-Lite
}
