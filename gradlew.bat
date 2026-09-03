@echo off
setlocal
set GRADLE_VERSION=8.13
set GRADLE_SHA256=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
set GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip
if "%GRADLE_USER_HOME%"=="" (
  set CACHE_ROOT=%USERPROFILE%\.gradle\decimen-bootstrap
) else (
  set CACHE_ROOT=%GRADLE_USER_HOME%\decimen-bootstrap
)
set INSTALL_DIR=%CACHE_ROOT%\gradle-%GRADLE_VERSION%
set ZIP_FILE=%CACHE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip

if not exist "%INSTALL_DIR%\bin\gradle.bat" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  if not exist "%ZIP_FILE%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%GRADLE_URL%' -OutFile '%ZIP_FILE%'"
    if errorlevel 1 exit /b 1
  )
  for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ZIP_FILE%').Hash.ToLower()"') do set ACTUAL_SHA=%%H
  if /I not "%ACTUAL_SHA%"=="%GRADLE_SHA256%" (
    del /q "%ZIP_FILE%"
    echo ERROR: Gradle archive checksum mismatch.
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP_FILE%' '%CACHE_ROOT%'"
  if errorlevel 1 exit /b 1
)

call "%INSTALL_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
