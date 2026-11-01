@echo off
rem Runs mvn inside a Visual Studio developer environment, for MainFrame's native-image builds.
rem
rem A plain shell has no rc.exe or MSVC link.exe on PATH, and under Git Bash /usr/bin/link.exe (coreutils)
rem shadows the real linker. This loads vcvars64 (located with vswhere) first, then runs mvn in the current
rem directory with whatever arguments it is given:
rem
rem     native-build.cmd -Pnative-release package -DskipTests
rem     native-build.cmd -Pnative package -DskipTests
rem
rem From Git Bash, ./native-build.cmd with the same arguments.
setlocal
set "VSDIR=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer"
if not exist "%VSDIR%\vswhere.exe" (
    echo native-build: vswhere.exe not found; is Visual Studio installed? 1>&2
    exit /b 1
)
set "VCVARS="
rem vswhere is run from its own directory: a quoted path with spaces inside for /f's command gets mangled, and
rem the current directory is not searched for commands, so it is named as .\vswhere.exe.
pushd "%VSDIR%"
for /f "usebackq delims=" %%i in (`.\vswhere.exe -latest -products * -find VC\Auxiliary\Build\vcvars64.bat 2^>nul`) do set "VCVARS=%%i"
popd
if not defined VCVARS (
    echo native-build: no vcvars64.bat found; install the "Desktop development with C++" workload. 1>&2
    exit /b 1
)
call "%VCVARS%" >nul || exit /b 1
mvn %*
exit /b %ERRORLEVEL%
