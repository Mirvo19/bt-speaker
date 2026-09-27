@echo off
setlocal
set "TRUST_HELPER=%TEMP%\bt-woofer-trust-receiver.ps1"

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; try { Invoke-WebRequest -UseBasicParsing -Uri 'https://mirvo19.github.io/bt-speaker/trust-receiver.ps1' -OutFile $env:TRUST_HELPER; & $env:TRUST_HELPER } catch { Write-Error $_; exit 1 }"
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" pause
del "%TRUST_HELPER%" >nul 2>&1
exit /b %RESULT%