#!/bin/sh
# Minimal, portable gradle wrapper. Invokes the gradle-wrapper.jar with java.
# Args are forwarded as-is.

set -e

APP_HOME=$(cd -P "$(dirname "$0")" > /dev/null && printf '%s\n' "$PWD")

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" \
    -Xmx2048m \
    -Dorg.gradle.appname=gradlew \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
