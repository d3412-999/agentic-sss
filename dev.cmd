@echo off
setlocal EnableExtensions
set "SCRIPT_DIR=%~dp0scripts"

if "%~1"=="" goto :usage
if /i "%~1"=="up"           goto :up
if /i "%~1"=="down"         goto :down
if /i "%~1"=="restart"      goto :restart
if /i "%~1"=="status"       goto :status
if /i "%~1"=="logs"         goto :logs
if /i "%~1"=="help"         goto :usage
if /i "%~1"=="backend-off"  goto :backendoff
if /i "%~1"=="frontend-off" goto :frontendoff
goto :usage

:up
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-up.ps1" %2 %3 %4 %5
exit /b %errorlevel%

:down
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-down.ps1" %2 %3
exit /b %errorlevel%

:backendoff
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-down.ps1" -BackendOnly %2
exit /b %errorlevel%

:frontendoff
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-down.ps1" -FrontendOnly %2
exit /b %errorlevel%

:restart
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-down.ps1"
if errorlevel 1 exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-up.ps1" %2 %3 %4 %5
exit /b %errorlevel%

:status
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-status.ps1"
exit /b %errorlevel%

:logs
if not exist "%~dp0logs" mkdir "%~dp0logs"
start "" explorer "%~dp0logs"
exit /b 0

:usage
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-status.ps1"
echo.
echo   dev up            start AI + backend + frontend (clean stale ports first)
echo   dev down          stop all three
echo   dev restart       down then up
echo   dev status        show ports / health / log paths (AI included)
echo   dev logs          open log folder
echo.
echo   dev backend-off   stop backend only  (hand port 8084 to the IDE)
echo   dev frontend-off  stop frontend only
echo.
echo   AI service :8000  uvicorn from langchain_service\.venv, started by "dev up";
echo                     skipped with a warning when .venv is missing or 8000 is taken.
echo.
echo   options:  dev up -Force         kill a port holder that cannot be identified
echo             dev up -BackendOnly
echo             dev up -FrontendOnly
echo.
exit /b 0
