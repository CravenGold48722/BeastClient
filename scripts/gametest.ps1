# Runs the in-game tests (Fabric client gametests) and shows only what
# matters: each test's progress lines while it runs, then PASSED or FAILED
# with the reason. Started by gametest.cmd in the repo root - see there.

param(
	[switch]$Update,
	[switch]$Mods,
	[switch]$Help,
	[Parameter(ValueFromRemainingArguments = $true)][string[]]$Tests
)

if($Help)
{
	Write-Host @"
gametest                   run every in-game test
gametest mace scaffold     only tests whose name contains "mace" or "scaffold"
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

# Native stderr shows up as ErrorRecords in Windows PowerShell; keep going.
$ErrorActionPreference = "Continue"

& .\gradlew.bat @gradleArgs 2>&1 | ForEach-Object { "$_" } |
	Tee-Object -FilePath $fullLog | ForEach-Object {
	$line = $_
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
