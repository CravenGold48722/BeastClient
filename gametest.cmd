@echo off
rem Runs the in-game tests and prints a short summary.
rem   gametest                 all tests
rem   gametest mace scaffold   only tests whose name contains these
rem   gametest -mods           with Sodium etc.
rem   gametest -update         re-record the screenshot templates
rem   gametest -help
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\gametest.ps1" %*
exit /b %ERRORLEVEL%
