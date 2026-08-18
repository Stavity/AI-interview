@echo off
setlocal

set "JAVA_HOME=D:\SoftWare\Java25"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 25 was not found at %JAVA_HOME%
  exit /b 1
)

cd /d "%~dp0.."
echo Using Java:
java -version
echo.
echo Starting backend with Gradle bootRun...
call gradlew.bat --no-daemon :app:bootRun
exit /b %ERRORLEVEL%
