@echo off
chcp 65001 >nul
title DV
cd /d "%~dp0pc"
python dv_server.py %*
pause
