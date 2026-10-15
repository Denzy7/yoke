@echo off
rem Runs Yoke from this checkout without installing it or setting PYTHONPATH.
rem Uses .\venv if it exists, otherwise python. Options are passed through, e.g. run-yoke --id 2
setlocal
set "HERE=%~dp0"
set "PY=%HERE%venv\Scripts\python.exe"
if not exist "%PY%" set "PY=python"
set "PYTHONPATH=%HERE%;%PYTHONPATH%"
"%PY%" -m yoke %*
