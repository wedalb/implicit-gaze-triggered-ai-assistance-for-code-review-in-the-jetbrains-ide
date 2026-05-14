#!/bin/bash
# Bootstrap script: downloads Gradle 8.6 and sets up the wrapper + gradlew.
set -e

GRADLE_VERSION="8.6"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Use PyCharm's bundled JDK if no JAVA_HOME is set
if [ -z "$JAVA_HOME" ]; then
    BUNDLED_JAVA=$(find "$HOME/Library/Application Support/JetBrains/Toolbox" \
        -name "java" -path "*/jbr/*/bin/java" 2>/dev/null | head -1)
    if [ -n "$BUNDLED_JAVA" ]; then
        export JAVA_HOME="$(dirname "$(dirname "$BUNDLED_JAVA")")"
        echo "Using JDK: $JAVA_HOME"
    fi
fi

GRADLE_ZIP="/tmp/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_DIR="/tmp/gradle-${GRADLE_VERSION}"

if [ ! -d "$GRADLE_DIR" ]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    curl -L "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
        -o "$GRADLE_ZIP"
    unzip -q -o "$GRADLE_ZIP" -d /tmp/
fi

echo "Generating Gradle wrapper..."
"$GRADLE_DIR/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin

chmod +x gradlew
echo ""
echo "Setup complete. Build the plugin with:"
echo "  JAVA_HOME=\"$JAVA_HOME\" ./gradlew buildPlugin"
echo ""
echo "The .zip will appear in: build/distributions/"
