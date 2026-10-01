#!/usr/bin/env bash
set -euo pipefail

git add .
if git diff --cached --quiet; then
  echo "Nothing new to commit."
else
  git commit -m "Add Dragonfist Trainer Android project and APK build"
fi
git push origin HEAD:main

echo
echo "Uploaded. GitHub Actions will now build the APK."
echo "Open the repository's Actions tab and select 'Build Dragonfist Trainer APK'."
