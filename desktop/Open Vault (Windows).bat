@echo off
setlocal
title DriveVault
cd /d "%~dp0DriveVault\app" || goto :nodir
set "PY="
py -3 --version >nul 2>&1 && set "PY=py -3"
if not defined PY (python --version >nul 2>&1 && set "PY=python")
if not defined PY goto :nopython
%PY% -c "import cryptography" >nul 2>&1 || goto :install
goto :argon

:install
echo Installing the encryption library - one time only...
%PY% -m pip install --user --disable-pip-version-check cryptography
%PY% -c "import cryptography" >nul 2>&1 || goto :installfail

:argon
rem Argon2id (vaults from DriveVault 1.8) needs cryptography 44+ or argon2-cffi
%PY% -c "import sys, vaultcore; sys.exit(0 if vaultcore.argon2_available() else 1)" >nul 2>&1 && goto :run
echo Updating the encryption library - one time only...
%PY% -m pip install --user --upgrade --disable-pip-version-check cryptography

:run
%PY% drivevault.py
timeout /t 3 >nul
goto :eof

:nopython
echo Python 3 is needed. Install it from python.org and tick "Add python.exe to PATH", then run this again.
start "" https://www.python.org/downloads/windows/
pause
goto :eof

:installfail
echo Could not install the "cryptography" package. Check the internet connection and try again.
pause
goto :eof

:nodir
echo The DriveVault folder was not found next to this file.
pause
