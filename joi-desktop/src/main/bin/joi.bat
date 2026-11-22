@echo off
rem Starts Joi (the server with its tray icon) on the bundled Java runtime. Options: joi --help
rem Extra JVM options can be passed in JOI_JAVA_OPTS (e.g. -Xmx2g).
setlocal
set "DIR=%~dp0.."
"%DIR%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED %JOI_JAVA_OPTS% -jar "%DIR%\app\joi-launcher.jar" %*
