@echo off
REM Builds TuneDesk.exe locally. Needs JDK 17+ and Gradle on PATH (or run from IntelliJ).
call gradle createDistributable
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
echo.
echo Done. Your app is in: build\compose\binaries\main\app\TuneDesk\TuneDesk.exe
pause
