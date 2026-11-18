#!/bin/sh
export JAVA_HOME=$HOME/setup/jdk
exec $HOME/setup/gradle/bin/gradle "$@"
