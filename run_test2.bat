@echo off
setlocal enabledelayedexpansion
set JAVA_HOME=C:\Progra~1\Eclipse Adoptium\jdk-17.0.20.101-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
set ANDROID_HOME=D:\Android\NASWebDAV\.android-sdk
set GRADLE_OPTS=-Dorg.gradle.java.home="%JAVA_HOME%"
cd /d D:\Android\NASWebDAV
gradlew.bat test --no-daemon
