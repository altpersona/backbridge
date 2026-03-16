@ECHO OFF
SETLOCAL

SET APP_HOME=%~dp0
SET CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

IF DEFINED JAVA_HOME (
    SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
    IF EXIST "%JAVA_EXE%" GOTO execute
    ECHO ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
    EXIT /B 1
)

SET JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
IF %ERRORLEVEL% NEQ 0 (
    ECHO ERROR: Java was not found in PATH and JAVA_HOME is not set.
    EXIT /B 1
)

:execute
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
