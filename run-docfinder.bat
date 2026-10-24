@echo off
setlocal enableextensions

rem 本脚本与 JAR 放在同目录
set "BASE=%~dp0"

rem 自动找最像样的 docfinder-*.jar / *-shaded.jar
set "JAR="
for /f "delims=" %%J in ('dir /b /a:-d "%BASE%docfinder-*-shaded.jar" 2^>nul') do set "JAR=%%J"
if not defined JAR for /f "delims=" %%J in ('dir /b /a:-d "%BASE%docfinder-*.jar" 2^>nul') do set "JAR=%%J"

if not defined JAR (
  echo [DocFinder] No JAR found next to this .bat.
  echo Please put this script in the same folder with the built JAR.
  pause
  exit /b 1
)

set "JAVA=%JAVA_HOME%\bin\javaw.exe"
if not exist "%JAVA%" set "JAVA=javaw.exe"

rem -Xms stays small so startup stays light; -Xmx only lets the heap grow
rem when the OS has free memory. Lower -Xmx if the machine is short on RAM.
start "" "%JAVA%" -Xms256m -Xmx2048m -Dfile.encoding=UTF-8 -jar "%BASE%%JAR%"
exit /b 0
