# Runs the in-game tests (Fabric client gametests) and shows only what
# matters: each test's progress lines while it runs, then PASSED or FAILED
# with the reason. Started by gametest.cmd in the repo root - see there.

# no positional binding: "gametest anticheat" must not land in -Features
[CmdletBinding(PositionalBinding = $false)]
param(
	[switch]$Update,
	[switch]$Mods,
	[switch]$NoSweep,
	[switch]$NoPerfMods,
	[string]$Features,
	[switch]$Help,
	[Parameter(ValueFromRemainingArguments = $true)][string[]]$Tests
)

if($Help)
{
	Write-Host @"
gametest                   run every in-game test
gametest mace scaffold     only tests whose name contains "mace" or "scaffold"
gametest anticheat         only the Grim and Intave servers (or: grim, intave)
gametest -nosweep          without the per-feature tests on them (~10 min less each)
gametest grim -features blink,criticals   only those features' tests (names contain)
gametest -noperfmods       without the performance mods from .minecraft\mods
                           (screenshot templates are recorded with them)
gametest -mods             with Sodium etc. installed
gametest -update           re-record the screenshot templates (check git diff!)
"@
	exit 0
}

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$task = "runClientGameTest"
$runDir = "build\run\clientGameTest"
if($Mods)
{
	$task = "runClientGameTestWithMods"
	$runDir = "build\run\clientGameTestWithMods"
}

$gradleArgs = @($task, "--console=plain")
if($Tests)
{
	$gradleArgs += "-Ponly=" + ($Tests -join ",")
}
if($NoSweep)
{
	$gradleArgs += "-PnoFeatureSweep"
}
if($NoPerfMods)
{
	$gradleArgs += "-PnoPerfMods"
}
if($Features)
{
	$gradleArgs += "-Pfeatures=$Features"
}
if($Update)
{
	$gradleArgs += "-PupdateScreenshots"
}

New-Item -ItemType Directory -Force "build" | Out-Null
$fullLog = Join-Path $root "build\gametest-output.log"
$start = Get-Date

if($Tests)
{
	Write-Host "Running in-game tests matching: $($Tests -join ', ')" -ForegroundColor Cyan
}else
{
	Write-Host "Running all in-game tests" -ForegroundColor Cyan
}
Write-Host "(the game window opens - don't close or click it; full output: build\gametest-output.log)" -ForegroundColor DarkGray

$script:complete = $false
$script:errors = New-Object System.Collections.Generic.List[string]
$script:nextIsCause = $false

# A test game or test server left over from an earlier run (stopped, hung,
# a computer restart mid-run...) would log in as the same bot and kick this
# run's off, or hold the server ports.
$leftovers = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
	Where-Object { $_.CommandLine -match "fabric\.client\.gametest|build[\\/]anticheat|fabric-server\.jar|paper\.jar" }
foreach($p in $leftovers)
{
	Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if($leftovers)
{
	Write-Host "(stopped $(@($leftovers).Count) test game/server process(es) left over from an earlier run)" -ForegroundColor DarkGray
}

# Native stderr shows up as ErrorRecords in Windows PowerShell; keep going.
$ErrorActionPreference = "Continue"

# Watchdog: the game log not changing for 4 minutes means the game hung (a
# deadlock in the test harness has happened). Save a thread dump, then stop
# the game and the anticheat test servers, so the run ends with a reason.
$hangMarker = Join-Path $root "build\gametest-hang.txt"
Remove-Item $hangMarker -ErrorAction SilentlyContinue
$watchdog = Start-Job -ArgumentList (Join-Path $root "$runDir\logs\latest.log"), $hangMarker -ScriptBlock {
	param($gameLog, $hangMarker)
	$startedAt = Get-Date
	while($true)
	{
		Start-Sleep -Seconds 15
		$game = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
			Where-Object { $_.CommandLine -match "fabric\.client\.gametest" } |
			Select-Object -First 1
		if(-not $game -or -not (Test-Path $gameLog))
		{
			continue
		}
		$last = (Get-Item $gameLog).LastWriteTime
		if($last -lt $startedAt -or ((Get-Date) - $last).TotalMinutes -lt 4)
		{
			continue
		}

		$jstack = Join-Path (Split-Path $game.ExecutablePath) "jstack.exe"
		"No game output for 4 minutes - thread dump:" | Out-File $hangMarker -Encoding utf8
		if(Test-Path $jstack)
		{
			& $jstack $game.ProcessId | Out-File $hangMarker -Append -Encoding utf8
		}
		Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
			Where-Object { $_.CommandLine -match "fabric\.client\.gametest|fabric-server\.jar|paper\.jar" } |
			ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
		return
	}
}

# UTF-8 (Tee-Object writes UTF-16 in Windows PowerShell); another file if
# that one is still held open (e.g. by an earlier run that hung)
$utf8 = New-Object System.Text.UTF8Encoding($false)
try
{
	$writer = New-Object System.IO.StreamWriter($fullLog, $false, $utf8)
}catch
{
	$fullLog = Join-Path $root ("build\gametest-output-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
	$writer = New-Object System.IO.StreamWriter($fullLog, $false, $utf8)
	Write-Host "(build\gametest-output.log is in use - writing $fullLog)" -ForegroundColor DarkGray
}

& .\gradlew.bat @gradleArgs 2>&1 | ForEach-Object { "$_" } | ForEach-Object {
	$line = $_
	$writer.WriteLine($line)
	if($line -match "\(Wurst Test\) (.*)$")
	{
		$msg = $Matches[1]
		if($msg -eq "Test complete")
		{
			$script:complete = $true
		}
		if($msg -match "^Testing ")
		{
			Write-Host "  $msg" -ForegroundColor Cyan
		}else
		{
			Write-Host "    $msg"
		}
		
	}elseif($line -match "Client gametests failed")
	{
		$script:nextIsCause = $true
		
	}elseif($script:nextIsCause -and $line -match "^\s*(\S*(Exception|Error)\S*): (.*)$")
	{
		# the exception right after "Client gametests failed": the reason
		$script:errors.Add($Matches[3])
		$script:nextIsCause = $false
		
	}elseif($line -match ": error: |^> Task \S+ FAILED" -and $line -notmatch "runClientGameTest")
	{
		# compile errors etc. from Gradle
		$script:errors.Add($line.Trim())
		Write-Host "  $($line.Trim())" -ForegroundColor Red
	}
}
$exitCode = $LASTEXITCODE
$writer.Close()
Stop-Job $watchdog -ErrorAction SilentlyContinue
Remove-Job $watchdog -Force -ErrorAction SilentlyContinue
if(Test-Path $hangMarker)
{
	$script:complete = $false
	$script:errors.Insert(0, "The game hung (no output for 4 minutes) and was stopped - thread dump in build\gametest-hang.txt")
}

$time = (Get-Date) - $start
$took = "{0}m {1:00}s" -f [int][math]::Floor($time.TotalMinutes), $time.Seconds
Write-Host ""

# A clean exit without "Test complete" means the game was closed early.
if($exitCode -eq 0 -and $script:complete)
{
	Write-Host "PASSED in $took" -ForegroundColor Green
	exit 0
}

Write-Host "FAILED after $took" -ForegroundColor Red
foreach($e in ($script:errors | Select-Object -Unique))
{
	Write-Host "  $e" -ForegroundColor Red
}
if($script:errors.Count -eq 0)
{
	if($exitCode -eq 0)
	{
		Write-Host "  The game exited before the tests finished (window closed?)" -ForegroundColor Red
	}else
	{
		Write-Host "  No reason found - see the end of build\gametest-output.log" -ForegroundColor Red
	}
}

$shots = Join-Path $root "$runDir\screenshots"
if(Test-Path $shots)
{
	$new = Get-ChildItem $shots -Filter *.png | Where-Object { $_.LastWriteTime -ge $start }
	if($new)
	{
		Write-Host "  Screenshots of this run: $shots" -ForegroundColor DarkGray
	}
}
Write-Host "  Game log: $runDir\logs\latest.log" -ForegroundColor DarkGray

if($exitCode -eq 0)
{
	$exitCode = 1
}
exit $exitCode
