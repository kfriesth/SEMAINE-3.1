@echo off
set BINDIR=%~dp0%

set MARYDIR="%BINDIR%\..\MARY400beta"

if not exist %MARYDIR% (
  echo.MARY directory not found in %MARYDIR%. Cannot start.
  goto end
)

set JARDIR="%BINDIR%..\java\lib"

if not exist %JARDIR%\semaine.jar (
  echo.No semaine.jar -- you need to do 'ant jars' in the java folder first!
  goto end
)

if %1%a==a (
  set CONFIG="%BINDIR%..\java\config\woz2speech.config"
) else (
  set CONFIG=%1%
)

if not %CMS_URL%a==a (
  echo.Connecting to JMS server at %CMS_URL%
  set JMS_URL_SETTING="-Djms.url=%CMS_URL%"
)


echo.Starting SEMAINE system as: 'java -Xmx1g %JMS_URL_SETTING% -classpath %JARDIR%\semaine.jar;%JARDIR%\semaine-mary.jar;%JARDIR%\semaine-dialogue.jar -Dmary.base="%MARYDIR%" eu.semaine.system.ComponentRunner %CONFIG%'

java -Xmx1g -Dlog.level=debug %JMS_URL_SETTING% -classpath %JARDIR%\semaine.jar;%JARDIR%\semaine-mary.jar;%JARDIR%\semaine-dialogue.jar -Dmary.base=%MARYDIR% eu.semaine.system.ComponentRunner %CONFIG%

:: goto target:

:end
set /P DUMMY=Press return to continue...
