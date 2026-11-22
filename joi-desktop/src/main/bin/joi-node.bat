@echo off
rem Starts the Joi node (the avatar, the microphone, the tools of this computer) on the bundled Java runtime. Without options it
rem joins the Joi of this computer when that runs. Extra JVM options can be passed in JOI_JAVA_OPTS.
setlocal
set "DIR=%~dp0.."
if "%JOI_DATA%"=="" (set "DATA=%USERPROFILE%\.joi-bundled-client") else (set "DATA=%JOI_DATA%")
set "HOME_DIR=%JOI_HOME_DIR%"
if "%HOME_DIR%"=="" set "HOME_DIR=%DATA%\node"
set "PORT=%JOI_PORT%"
if "%PORT%"=="" set "PORT=8766"
if "%~1"=="" if exist "%DATA%\desktop\api.sock" (
  "%DIR%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED %JOI_JAVA_OPTS% -cp "%DIR%\app\*" com.github.enerccio.joi.node.fx.Launcher --local "%DATA%\desktop\api.sock" --web "http://127.0.0.1:%PORT%" --joi-home-dir "%HOME_DIR%"
  exit /b %ERRORLEVEL%
)
"%DIR%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED %JOI_JAVA_OPTS% -cp "%DIR%\app\*" com.github.enerccio.joi.node.fx.Launcher %*
