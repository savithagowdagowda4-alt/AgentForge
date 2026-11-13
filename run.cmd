@echo off
rem Builds (if needed) and starts AgentForge on http://localhost:8080
rem Optional: set ANTHROPIC_API_KEY=... before running to enable Claude reasoning and vision.
setlocal
cd /d "%~dp0"
set "MAVEN_OPTS=-Duser.home=%~dp0.home -Dmaven.repo.local=%~dp0.home\.m2\repository"
if not exist "target\agentforge-2.0.0.jar" (
  call "%~dp0.tools\apache-maven-3.9.9\bin\mvn.cmd" -B -q package -DskipTests || exit /b 1
)
java -jar "target\agentforge-2.0.0.jar"
