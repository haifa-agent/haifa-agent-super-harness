@echo off
REM Stop the processes started by quick-start.bat (uses config\run.json PIDs only).
setlocal
set "PREFIX=%~dp0"
set "PREFIX=%PREFIX:~0,-1%"

where python >nul 2>nul
if errorlevel 1 (
    echo python ^(^>= 3.8^) is required. 1>&2
    exit /b 1
)

python "%PREFIX%\scripts\install-service.py" stop --prefix "%PREFIX%" %*
exit /b %ERRORLEVEL%
