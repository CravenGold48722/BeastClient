@echo off
rem Stops the neural network training (train.cmd or the nightly task).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\train.ps1" stop
exit /b %ERRORLEVEL%
