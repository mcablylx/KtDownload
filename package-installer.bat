@echo off
setlocal

REM Prefer an installed WiX 3.x over the bundled 3.11 copy: some Windows
REM environments reject the bundled tool's temp-directory ACL creation.
if not defined WIX_PATH (
    for /d %%d in ("%ProgramFiles(x86)%\WiX Toolset v*") do (
        if exist "%%~fd\bin\light.exe" set "WIX_PATH=%%~fd\bin"
    )
    if not defined WIX_PATH (
        for /d %%d in ("%ProgramFiles%\WiX Toolset v*") do (
            if exist "%%~fd\bin\light.exe" set "WIX_PATH=%%~fd\bin"
        )
    )
)

echo ====================================
echo Build KtDownload Windows EXE Installer
echo ====================================
echo.

REM Print the WiX actually in use: a wrong pick here is the most common
REM packaging failure. The bundled 3.11 sometimes fails with
REM "error LGHT0001 : the client does not hold the required privilege",
REM which jpackage only reports as an invalid/unsupported [exe] type.
REM NOTE: keep these as single-line ifs. A parenthesized block would break
REM on %WIX_PATH%, whose value contains "(x86)".
if not defined WIX_PATH echo   ^>^> No installed WiX found, bundled 3.11 will be used.
if not defined WIX_PATH echo   ^>^> If packaging fails at packageReleaseExe/Msi with an
if not defined WIX_PATH echo   ^>^> invalid light.exe version, install WiX v3.14 or set WIX_PATH.
if defined WIX_PATH echo WiX toolset: "%WIX_PATH%"
echo.
echo JDK: %JAVA_HOME%
echo.

REM A Low integrity label on this folder is inherited by everything built here,
REM and a Low process cannot write %TEMP%, so the installer dies with
REM "createTempFile(.tmp, ...) failed" even though the build itself succeeded.
icacls "%~dp0." | findstr /c:"Low Mandatory Level" >nul
if not errorlevel 1 (
    echo   ^>^> WARNING: this folder carries a Low integrity label.
    echo   ^>^> Installers built here may fail to install with
    echo   ^>^> "createTempFile(.tmp, ...) failed".
    echo   ^>^> Fix: icacls "%~dp0." /setintegritylevel "(OI)(CI)Medium"
    echo.
)

echo Cleaning old build...
call "%~dp0gradlew.bat" clean
if errorlevel 1 (
    echo Clean failed!
    pause
    exit /b 1
)

echo.
echo Fetching bundled FFmpeg (pinned version, SHA-256 verified)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\fetch-ffmpeg.ps1"
if errorlevel 1 (
    echo FFmpeg download failed!
    pause
    exit /b 1
)

echo.
echo Packaging application (may take a few minutes)...
call "%~dp0gradlew.bat" composeApp:packageReleaseDistributionForCurrentOS
if errorlevel 1 (
    echo Packaging failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo Packaging completed!
echo ====================================
echo.
for /f "tokens=2 delims==" %%a in ('findstr /b /c:"appVersion=" "%~dp0gradle.properties"') do set "APP_VERSION=%%a"
if not defined APP_VERSION set "APP_VERSION=unknown"
echo Installer locations:
echo composeApp\build\compose\binaries\main-release\exe\KtDownload-%APP_VERSION%.exe
echo composeApp\build\compose\binaries\main-release\msi\KtDownload-%APP_VERSION%.msi
echo.
echo Note:
echo - If the EXE reports createTempFile(.tmp, ...) failed, copy it to a normal
echo   folder (or run: icacls "<exe>" /setintegritylevel Medium); the MSI also works.
echo - Start menu shortcut: KtDownload
echo - Desktop shortcut: enabled by jpackage shortcut = true
echo.

pause
endlocal
