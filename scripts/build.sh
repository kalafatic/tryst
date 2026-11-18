#!/usr/bin/env bash
# TRYST - Build Script

set -euo pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "${SCRIPT_DIR}/.."

JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1)"
if [[ "${JAVA_MAJOR}" != "21" && -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
    export PATH="${JAVA_HOME}/bin:${PATH}"
    JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1)"
fi
if [[ "${JAVA_MAJOR}" != "21" ]]; then
    echo "ERROR: JDK 21 is required. Set JAVA_HOME to a JDK 21 installation." >&2
    exit 1
fi

if [[ -x ./mvnw ]]; then
    MVN=./mvnw
else
    MVN="${MVN_CMD:-mvn}"
fi
"${MVN}" -version >/dev/null 2>&1 || { echo "Maven not found. Set MVN_CMD or install Maven 3.9+." >&2; exit 1; }
MVN_VERSION="$("${MVN}" -version 2>/dev/null | awk '/^Apache Maven/{print $3; exit}')"
if ! awk -v v="${MVN_VERSION}" 'BEGIN { split(v,a,"."); if (a[1] > 3 || (a[1] == 3 && a[2] >= 9)) exit 0; exit 1 }'; then
    echo "ERROR: Maven ${MVN_VERSION} is too old for Tycho 4.0.8; use Maven 3.9+ (e.g. MVN_CMD=/path/to/maven-3.9/bin/mvn)." >&2
    exit 1
fi

echo "Building TRYST with Java: $(java -version 2>&1 | grep -m1 version)"
"${MVN}" -B -DskipTests clean verify

echo "Build successful. Product: product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64/tryst"
