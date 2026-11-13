@echo off
rem Double-click to start AgentForge with Claude enabled.
rem Asks for your Anthropic API key (kept only for this window), then starts the server.
setlocal
cd /d "%~dp0"
netstat -ano | findstr /R /C:":8080 .*LISTENING" >nul && (
  echo Port 8080 is already in use - AgentForge may already be running.
  echo Close the other AgentForge window first, or open http://localhost:8080
  pause
  exit /b 1
)
if "%ANTHROPIC_API_KEY%"=="" set /p ANTHROPIC_API_KEY=Paste your Anthropic API key and press Enter:
if "%ANTHROPIC_API_KEY%"=="" echo No key entered - starting in rule-based mode.
echo.
echo Starting AgentForge... open http://localhost:8080 once you see "Started AgentForgeApplication".
echo Keep this window open while you use the app. Close it to stop the server.
echo.
start "" http://localhost:8080
call "%~dp0run.cmd"
pause
