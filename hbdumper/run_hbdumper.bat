@echo off
setlocal ENABLEDELAYEDEXPANSION
cd /d "%~dp0"

set "BUNDLED_SCRIPTS=%~dp0bin\openocd\extracted\xpack-openocd-0.12.0-7\openocd\scripts"
set "BUNDLED_OPENOCD=%~dp0bin\openocd\extracted\xpack-openocd-0.12.0-7\bin\openocd.exe"

if exist "%BUNDLED_SCRIPTS%" (
  set "OPENOCD_SCRIPTS=%BUNDLED_SCRIPTS%"
)

echo ============================================================
echo HB Dumper launcher
echo Project root: %CD%
if exist "%BUNDLED_OPENOCD%" (
  echo Bundled OpenOCD found: %BUNDLED_OPENOCD%
) else (
  echo Bundled OpenOCD not found. You can still point the GUI at any OpenOCD install.
)
echo ============================================================

where py >nul 2>nul
if not errorlevel 1 (
  set "PYEXE=py -3"
) else (
  where python >nul 2>nul
  if not errorlevel 1 (
    set "PYEXE=python"
  ) else (
    echo Python 3 was not found in PATH.
    echo Install Python 3 for Windows, then rerun this launcher.
    pause
    exit /b 1
  )
)

%PYEXE% -c "import serial" >nul 2>nul
if errorlevel 1 (
  echo Installing pyserial...
  %PYEXE% -m pip install pyserial
)

%PYEXE% src\hbdumper.py
set ERR=%ERRORLEVEL%
if not "%ERR%"=="0" (
  echo.
  echo HB Dumper exited with code %ERR%
  pause
)
exit /b %ERR%
