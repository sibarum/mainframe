@echo off
rem The main path, driven by ottermate on a home of the run's own (docs/shipping.md). The exit status is the verdict:
rem 0 every step answered ok, 1 a step did not (its reply says which), 3 or 4 the application did not start or went away.
rem
rem     checks\main-path.cmd          the native debug build (native-build.cmd -Pnative package -DskipTests)
rem     checks\main-path.cmd jvm      on the JVM, through mvn exec:exec
rem
rem OTTERMATE names the client; it defaults to the sibling vexelray-gui checkout's. main-path.sh is the same for
rem Git Bash and Linux.
setlocal
set "HERE=%~dp0"
for %%i in ("%HERE%..") do set "ROOT=%%~fi"
set "TARGET=%ROOT%\mainframe-gui\target"
set "CHECKHOME=%TARGET%\check-home"
set "SHOTS=%TARGET%\check"
if not defined OTTERMATE set "OTTERMATE=%ROOT%\..\vexelray-gui\vexelray-gui-automation-cli\ottermate.cmd"

rem A home with one workspace, day, whose two folders are in it.
if exist "%CHECKHOME%" rmdir /s /q "%CHECKHOME%"
if exist "%SHOTS%" rmdir /s /q "%SHOTS%"
mkdir "%CHECKHOME%\work" "%CHECKHOME%\web" "%CHECKHOME%\.mainframe\workspaces" "%SHOTS%"
> "%CHECKHOME%\.mainframe\workspaces\day.csv" (
    echo name,path,env
    echo ,%CHECKHOME%\work,work-c0ffee.ps1
    echo front,%CHECKHOME%\web,front-c0ffee.ps1
)

rem The script, with the shots' folder in it in forward slashes, and no byte-order mark before its first line.
set "SHOTS_FWD=%SHOTS:\=/%"
powershell -NoProfile -Command "[IO.File]::WriteAllText('%TARGET%\main-path.script', [IO.File]::ReadAllText('%HERE%main-path.txt').Replace('@SHOTS@', '%SHOTS_FWD%'))" || exit /b 2

cd /d "%ROOT%"
if /i "%~1"=="jvm" (
    call "%OTTERMATE%" --script "%TARGET%\main-path.script" --launch mvn.cmd exec:exec -pl mainframe-gui -Dautomation=0 "-Dapp.jvmArgs=-Duser.home=%CHECKHOME% -Dmainframe.launch=off"
) else (
    call "%OTTERMATE%" --script "%TARGET%\main-path.script" --launch "%TARGET%\mainframe-debug.exe" "-Duser.home=%CHECKHOME%" -Dmainframe.launch=off --automation=0
)
set "STATUS=%ERRORLEVEL%"
if not "%STATUS%"=="0" (
    echo main-path: ottermate stopped with status %STATUS%; the shots so far are in %SHOTS% 1>&2
    exit /b %STATUS%
)

rem What the Workspaces window did, on disk: the workspace made, named and redone is there, and day is as it was.
set "WS=%CHECKHOME%\.mainframe\workspaces"
if not exist "%WS%\evening.csv" goto :wrong
if exist "%WS%\New workspace.csv" goto :wrong
if not exist "%WS%\day.csv" goto :wrong
echo main-path: ok; the shots are in %SHOTS%
exit /b 0

:wrong
echo main-path: the workspaces folder is not as the steps left it: 1>&2
dir /b /a "%WS%" 1>&2
exit /b 1
