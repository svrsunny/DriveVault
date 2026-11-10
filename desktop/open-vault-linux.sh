#!/usr/bin/env sh
# DriveVault launcher for Linux (any distro). Run:  sh open-vault-linux.sh
cd "$(dirname "$0")/DriveVault/app" || { echo "DriveVault folder not found next to this script."; exit 1; }

PY=$(command -v python3 || command -v python)
[ -z "$PY" ] && { echo "Python 3 is required. Install it with your package manager (python3)."; exit 1; }

if ! "$PY" -c "import cryptography" 2>/dev/null; then
  echo "Installing the 'cryptography' Python package (one time)..."
  if   command -v apt-get >/dev/null; then sudo apt-get install -y python3-cryptography
  elif command -v dnf     >/dev/null; then sudo dnf install -y python3-cryptography
  elif command -v pacman  >/dev/null; then sudo pacman -S --noconfirm python-cryptography
  elif command -v zypper  >/dev/null; then sudo zypper install -y python3-cryptography
  elif command -v apk     >/dev/null; then sudo apk add py3-cryptography
  else "$PY" -m pip install --user cryptography
  fi
  "$PY" -c "import cryptography" 2>/dev/null || "$PY" -m pip install --user cryptography || { echo "Could not install 'cryptography'."; exit 1; }
fi

# Argon2id (vaults from DriveVault 1.8) needs cryptography 44+ or argon2-cffi; distro cryptography is often older.
if ! "$PY" -c "import sys, vaultcore; sys.exit(0 if vaultcore.argon2_available() else 1)" 2>/dev/null; then
  echo "Installing Argon2 support (one time)..."
  if   command -v apt-get >/dev/null; then sudo apt-get install -y python3-argon2
  elif command -v dnf     >/dev/null; then sudo dnf install -y python3-argon2-cffi
  elif command -v pacman  >/dev/null; then sudo pacman -S --noconfirm python-argon2_cffi
  elif command -v zypper  >/dev/null; then sudo zypper install -y python3-argon2-cffi
  elif command -v apk     >/dev/null; then sudo apk add py3-argon2-cffi
  else "$PY" -m pip install --user argon2-cffi
  fi
  "$PY" -c "import sys, vaultcore; sys.exit(0 if vaultcore.argon2_available() else 1)" 2>/dev/null ||
    echo "Argon2 support could not be installed. Older vaults still open; vaults made by DriveVault 1.8 need it."
fi
exec "$PY" drivevault.py "$@"
