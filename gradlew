#!/bin/sh

APP_HOME=$(cd "${0%/*}" && pwd -P)
APP_BASE_NAME=${0##*/}

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

if [ ! -x "$JAVACMD" ]; then
    echo "Java nie została znaleziona. Ustaw zmienną JAVA_HOME." >&2
    exit 1
fi

exec "$JAVACMD" "-Dorg.gradle.appname=$APP_BASE_NAME" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
