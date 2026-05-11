@echo off
REM ============================================================================
REM MasterDnsVPN - Build Go Mobile Library for Android
REM Prerequisites: Go 1.25+, gomobile, Android NDK
REM ============================================================================

set /p MOBILE_TOOLS_VERSION=<"%~dp0gomobile.version"
echo ===================================
echo MasterDnsVPN - Android Build Script
echo ===================================
echo.

REM Check go
where go >nul 2>&1
if errorlevel 1 (
    echo ERROR: Go is not installed or not in PATH
    echo Download from: https://go.dev/dl/
    pause
    exit /b 1
)

cd /d "%~dp0.."

git diff --quiet -- go.mod go.sum
if errorlevel 1 (
    echo.
    echo ERROR: go.mod/go.sum already have local changes.
    echo Refusing to build because android builds must not modify shared Go module files.
    git diff -- go.mod go.sum
    pause
    exit /b 1
)

REM Install pinned gomobile toolchain
go install golang.org/x/mobile/cmd/gomobile@%MOBILE_TOOLS_VERSION%
go install golang.org/x/mobile/cmd/gobind@%MOBILE_TOOLS_VERSION%
if errorlevel 1 (
    echo ERROR: Failed to install gomobile tools
    pause
    exit /b 1
)

gomobile init
if errorlevel 1 (
    echo ERROR: gomobile init failed
    pause
    exit /b 1
)

echo.
echo [1/2] Building Go mobile library...
echo.

set "SOURCE_ROOT=%CD%"
set "BUILD_ROOT=%TEMP%\masterdnsvpn-gomobile-%RANDOM%%RANDOM%"
mkdir "%BUILD_ROOT%"
if errorlevel 1 (
    echo ERROR: Failed to create temporary build directory
    pause
    exit /b 1
)

robocopy "%SOURCE_ROOT%" "%BUILD_ROOT%" /MIR /XD .git android\.gradle android\app\build android\build >nul
if errorlevel 8 (
    echo ERROR: Failed to copy source tree for isolated gomobile build
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

cd /d "%BUILD_ROOT%"

REM Resolve gomobile's bind dependency only in this temporary build tree.
GO111MODULE=on go get golang.org/x/mobile@%MOBILE_TOOLS_VERSION%
if errorlevel 1 (
    echo ERROR: Failed to prepare temporary gomobile module dependencies
    cd /d "%SOURCE_ROOT%"
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

GO111MODULE=on go mod download
if errorlevel 1 (
    echo ERROR: Failed to download temporary gomobile module dependencies
    cd /d "%SOURCE_ROOT%"
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

gomobile bind -v -target=android/arm64,android/arm,android/amd64,android/386 -androidapi 21 -o "%SOURCE_ROOT%\android\app\libs\masterdnsvpn.aar" ./mobile/

if errorlevel 1 (
    echo.
    echo ERROR: gomobile bind failed!
    echo Make sure ANDROID_HOME and ANDROID_NDK_HOME are set correctly.
    echo.
    echo ANDROID_HOME should point to: %LOCALAPPDATA%\Android\Sdk
    echo ANDROID_NDK_HOME should point to: %LOCALAPPDATA%\Android\Sdk\ndk\(version)
    cd /d "%SOURCE_ROOT%"
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

cd /d "%SOURCE_ROOT%"
rmdir /s /q "%BUILD_ROOT%"

git diff --quiet -- go.mod go.sum
if errorlevel 1 (
    echo.
    echo ERROR: gomobile build modified shared Go module files.
    echo Refusing to continue because android builds must not change go.mod/go.sum.
    git diff -- go.mod go.sum
    pause
    exit /b 1
)

echo.
echo [2/2] Go mobile library built successfully!
echo Output: android/app/libs/masterdnsvpn.aar
echo.
echo You can now open the android/ folder in Android Studio and build the APK.
echo Or run: cd android ^&^& gradlew assembleDebug
echo.
pause
