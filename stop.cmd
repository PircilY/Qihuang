@echo off
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\stop-local.ps1" %*
