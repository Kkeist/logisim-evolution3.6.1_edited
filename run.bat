@echo off
REM Use JDK 21 without requiring JAVA_HOME to be set system-wide
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERROR: Java not found at %JAVA_HOME%
  echo Please install JDK 21 to that path or edit this batch file.
  pause
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
call gradlew.bat run
pause
