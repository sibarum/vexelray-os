#!/usr/bin/env bash
# The M0 spike. Usage: compositor/run.sh run|sweep [out-dir] [offscreen]
#   run    the compositor and three program processes through the scripted phases (docs/m0-spike.md)
#   sweep  raster cost against instance count, no programs
#   offscreen  draw into an offscreen target and save the last frame as a PNG
set -euo pipefail
cd "$(dirname "$0")"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
exec java --enable-native-access=ALL-UNNAMED -cp "target/classes:$(cat target/classpath.txt)" \
  dev.vexelray.compositor.spike.Compositor "$@"
