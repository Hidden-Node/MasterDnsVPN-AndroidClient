@echo off
REM ============================================================================
REM MasterDnsVPN - Build Go Mobile Library for Android
REM Prerequisites: Go 1.25+, gomobile, Android NDK
REM ============================================================================

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

REM Check gomobile
where gomobile >nul 2>&1
if errorlevel 1 (
    echo gomobile not found, installing...
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
    gomobile init
    if errorlevel 1 (
        echo ERROR: Failed to install gomobile
        pause
        exit /b 1
    )
)

echo.
echo [1/2] Building Go mobile library...
echo.

cd /d "%~dp0.."
gomobile bind -v -target=android/arm64,android/arm,android/amd64,android/386 -androidapi 21 -o android/app/libs/masterdnsvpn.aar ./mobile/

if errorlevel 1 (
    echo.
    echo ERROR: gomobile bind failed!
    echo Make sure ANDROID_HOME and ANDROID_NDK_HOME are set correctly.
    echo.
    echo ANDROID_HOME should point to: %LOCALAPPDATA%\Android\Sdk
    echo ANDROID_NDK_HOME should point to: %LOCALAPPDATA%\Android\Sdk\ndk\(version)
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
