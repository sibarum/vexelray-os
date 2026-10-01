# Installed to /etc/profile.d/ at build; JNI headers work out of the box.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export CPATH="$JAVA_HOME/include:$JAVA_HOME/include/linux${CPATH:+:$CPATH}"
