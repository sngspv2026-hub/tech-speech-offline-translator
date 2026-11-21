@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0build_and_test_apk.ps1" %*
