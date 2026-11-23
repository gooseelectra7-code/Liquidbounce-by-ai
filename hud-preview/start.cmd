@echo off
rem Serves the HUD preview on localhost and opens it. The page needs a server because it loads
rem the fonts and the item textures — file:// blocks those.
setlocal
cd /d "%~dp0"
start "" http://127.0.0.1:8765/index.html
python -m http.server 8765 --bind 127.0.0.1
