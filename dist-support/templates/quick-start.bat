@echo off
REM Quick start without installing a service (no admin required).
REM Starts the Gateway + frozen frontend in the background and writes logs under logs\.
setlocal
set "PREFIX=%~dp0"
set "PREFIX=%PREFIX:~0,-1%"

where python >nul 2>nul
if errorlevel 1 (
    echo python ^(^>= 3.8^) is required. 1>&2
    exit /b 1
)

python "%PREFIX%\scripts\install-service.py" start --prefix "%PREFIX%" %*
exit /b %ERRORLEVEL%
