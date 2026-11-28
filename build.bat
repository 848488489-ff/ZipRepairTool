@echo off
setlocal
rem Build script for mcpack-deobfuscator (requires JDK 8+).
rem Sources are listed explicitly because javac does not expand wildcards
rem reliably when the path contains non-ASCII characters.

set "JDK8=D:\zulu java8\bin"
set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "OUT=%ROOT%out"
set "JAR=%ROOT%mcpack-deobfuscator.jar"
set "PKG=%SRC%\com\github\_848488489_ff\mcpack_deobfuscator"
set "MAIN=com.github._848488489_ff.mcpack_deobfuscator.McpackDeobfuscator"

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo [1/3] Compiling with JDK 8...
"%JDK8%\javac.exe" -encoding UTF-8 -d "%OUT%" ^
  "%PKG%\Lang.java" ^
  "%PKG%\ContentDeobfuscator.java" ^
  "%PKG%\ContentObfuscator.java" ^
  "%PKG%\CheckEngine.java" ^
  "%PKG%\DeobfuscateEngine.java" ^
  "%PKG%\ObfuscateEngine.java" ^
  "%PKG%\McpackDeobfuscator.java"
if errorlevel 1 exit /b 1

echo [2/3] Copying resources...
copy /y "%SRC%\messages_English.properties" "%OUT%\" >nul
copy /y "%SRC%\messages_Chinese.properties" "%OUT%\" >nul
copy /y "%ROOT%version.json" "%OUT%\" >nul
copy /y "%ROOT%LICENSE" "%OUT%\" >nul

echo [3/3] Packaging JAR...
"%JDK8%\jar.exe" cfe "%JAR%" %MAIN% -C "%OUT%" .
if errorlevel 1 exit /b 1

echo.
echo Built: %JAR%
endlocal
