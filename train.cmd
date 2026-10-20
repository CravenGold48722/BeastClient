@echo off
rem Trains AimAssist's neural network against itself in the background.
rem   train                start (runs until stoptrain)
rem   train -Until 07:00   start, stops by itself at 07:00
rem   train lite           super light background training (1 thread, ~10%, idle priority)
rem   train status         progress
rem   train install        train every night (22:30-07:30, only if the PC is free)
rem   train uninstall      no more nightly training
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\train.ps1" %*
exit /b %ERRORLEVEL%
