@echo off
title David voice server
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0david-server.ps1" %*
pause
