@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-Command','& ''%~dp0fix_adb.ps1'' *> ''%~dp0fix_log.txt''; Get-Content ''%~dp0fix_log.txt''; Read-Host ''按回车关闭'''"
