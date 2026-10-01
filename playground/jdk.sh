#!/usr/bin/env bash
# Install a newer JDK (Temurin) side by side with Ubuntu's JDK 21 and make it the default.
# Usage: playground/jdk.sh [version]   (default 25, the current LTS)
set -euo pipefail
V="${1:-25}"
DEST="/opt/jdk-$V"
if [ ! -x "$DEST/bin/java" ]; then
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/jdk.tgz" \
    "https://api.adoptium.net/v3/binary/latest/$V/ga/linux/x64/jdk/hotspot/normal/eclipse"
  sudo mkdir -p "$DEST"
  sudo tar -xzf "$tmp/jdk.tgz" -C "$DEST" --strip-components=1
  rm -rf "$tmp"
fi
for t in java javac jar jshell jextract; do
  [ -x "$DEST/bin/$t" ] && sudo update-alternatives --install "/usr/bin/$t" "$t" "$DEST/bin/$t" $((V*100))
done
echo "export JAVA_HOME=$DEST" | sudo tee /etc/profile.d/vexelray-java.sh >/dev/null
echo 'export CPATH="$JAVA_HOME/include:$JAVA_HOME/include/linux${CPATH:+:$CPATH}"' | sudo tee -a /etc/profile.d/vexelray-java.sh >/dev/null
"$DEST/bin/java" -version
echo "Open a new shell (or: source /etc/profile.d/vexelray-java.sh) to pick up JAVA_HOME."
