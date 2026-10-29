# Build environment for the Eneverre Android app.
#
# This image only contains the JDK + Android SDK. The project sources are NOT
# copied in: mount them at run time so the same image can build any checkout.
# Gradle itself is provided by the project's wrapper (gradle 9.7.0).
#
# Build the image:
#   docker build -t eneverre-builder .
#
# Compile the debug APK (output lands in app/build/ on the host):
#   docker run --rm --user "$(id -u):$(id -g)" \
#       -e HOME=/project/.gradle-docker -e GRADLE_USER_HOME=/project/.gradle-docker/gradle \
#       -v "$PWD":/project:z eneverre-builder
#
# Running as the host user keeps build outputs owned by you (not root). The ':z'
# on the project mount is required on SELinux hosts (Fedora/RHEL); it is harmless
# elsewhere. The SDK is made world-writable so any runtime UID can use it.
#
# See docker-build.sh for a convenience wrapper.

# AGP 9 / Gradle 9 need a modern JDK to run; 21 is what the project is built
# with locally (the Android Studio JBR). The app still targets Java 11 bytecode.
FROM eclipse-temurin:21-jdk-jammy

# --- Android SDK ---------------------------------------------------------
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

# Versions required by app/build.gradle (compileSdk 37, i.e. Android 17). Note
# the platform package carries a minor version: it is `android-37.0`, not
# `android-37`. The command-line tools must be recent enough to know about it.
ARG CMDLINE_TOOLS_VERSION=15859902
ARG ANDROID_PLATFORM=37.0
ARG BUILD_TOOLS_VERSION=37.0.0

RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Install the Android command-line tools under cmdline-tools/latest.
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" && \
    cd /tmp && \
    curl -fsSL -o tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" && \
    unzip -q tools.zip -d "${ANDROID_HOME}/cmdline-tools" && \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" && \
    rm tools.zip

ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"

# Accept licenses and install the SDK packages the build needs.
RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager --install \
        "platform-tools" \
        "platforms;android-${ANDROID_PLATFORM}" \
        "build-tools;${BUILD_TOOLS_VERSION}" > /dev/null && \
    chmod -R a+rwX "${ANDROID_HOME}"

WORKDIR /project

# Default: build the debug APK. Override the command to run lint, tests, etc.
CMD ["./gradlew", "assembleDebug", "--no-daemon"]
