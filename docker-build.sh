#!/usr/bin/env bash
# Build the Eneverre Android app inside Docker.
#
# Usage:
#   ./docker-build.sh                       # builds the debug APK (assembleDebug)
#   ./docker-build.sh lint                  # run any gradle task(s) instead
#   ./docker-build.sh assembleDebug installDebug
#
# Build outputs appear in app/build/ on the host and are owned by the calling
# user (the container runs as your UID:GID), so Android Studio can still clean
# them. The Gradle home / caches live in .gradle-docker/ and persist between
# runs, so dependencies are downloaded only once.
set -euo pipefail

IMAGE=eneverre-builder
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_HOME_DIR="${PROJECT_DIR}/.gradle-docker"

# Build the image if it is missing (cheap no-op once cached).
if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    echo ">> Building Docker image '${IMAGE}'..."
    docker build -t "${IMAGE}" "${PROJECT_DIR}"
fi

# Gradle tasks: default to a debug build.
if [ "$#" -eq 0 ]; then
    set -- assembleDebug
fi

# Created by us so it is owned by the host user (writable inside the container).
mkdir -p "${GRADLE_HOME_DIR}"

echo ">> Running: ./gradlew $*"
# --user runs the build as the host user so outputs aren't owned by root.
# HOME/GRADLE_USER_HOME point into the (host-owned) project so the debug
# keystore and Gradle caches are writable.
# The ':z' label on the project mount lets the container access the files on
# SELinux hosts (Fedora/RHEL); it is harmless elsewhere.
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/project/.gradle-docker \
    -e GRADLE_USER_HOME=/project/.gradle-docker/gradle \
    -v "${PROJECT_DIR}":/project:z \
    "${IMAGE}" \
    ./gradlew "$@" --no-daemon
