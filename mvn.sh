#!/usr/bin/env bash
# Runs the bundled Maven with a home directory free of '!' characters.
DIR="$(cd "$(dirname "$0")" && pwd)"
export MAVEN_OPTS="-Duser.home=$DIR/.home -Dmaven.repo.local=$DIR/.home/.m2/repository $MAVEN_OPTS"
exec "$DIR/.tools/apache-maven-3.9.9/bin/mvn" "$@"
