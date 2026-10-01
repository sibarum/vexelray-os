#!/usr/bin/env bash
# Run inside the booted image (or over ssh -p 2222). Exits nonzero on first failure.
set -euo pipefail
echo "== vulkan";  vulkaninfo --summary | grep -E 'deviceName|deviceType'
echo "== java";    java -version
echo "== jni"
d=$(mktemp -d); cd "$d"
cat > H.java <<'J'
public class H { static { System.loadLibrary("h"); } static native int f(); public static void main(String[] a){ if (f()!=42) System.exit(1); System.out.println("jni ok"); } }
J
cat > h.c <<'C'
#include <jni.h>
JNIEXPORT jint JNICALL Java_H_f(JNIEnv *e, jclass c){ return 42; }
C
gcc -shared -fPIC -o libh.so h.c   # uses CPATH from /etc/profile.d
java -Djava.library.path=. H.java
echo "ALL OK"
