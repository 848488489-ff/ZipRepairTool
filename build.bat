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

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo [1/3] Compiling with JDK 8...
"%JDK8%\javac.exe" -encoding UTF-8 -d "%OUT%" ^
  "%SRC%\com\mcpackdeobfuscator\Lang.java" ^
  "%SRC%\com\mcpackdeobfuscator\ContentRepairer.java" ^
  "%SRC%\com\mcpackdeobfuscator\ContentObfuscator.java" ^
  "%SRC%\com\mcpackdeobfuscator\CheckEngine.java" ^
  "%SRC%\com\mcpackdeobfuscator\RepairEngine.java" ^
  "%SRC%\com\mcpackdeobfuscator\ObfuscateEngine.java" ^
  "%SRC%\com\mcpackdeobfuscator\McpackDeobfuscator.java"
if errorlevel 1 exit /b 1

echo [2/3] Copying resources...
copy /y "%SRC%\messages_English.properties" "%OUT%\" >nul
copy /y "%SRC%\messages_Chinese.properties" "%OUT%\" >nul
copy /y "%ROOT%version.json" "%OUT%\" >nul

echo [3/3] Packaging JAR...
"%JDK8%\jar.exe" cfe "%JAR%" com.mcpackdeobfuscator.McpackDeobfuscator -C "%OUT%" .
if errorlevel 1 exit /b 1

echo.
echo Built: %JAR%
endlocal
