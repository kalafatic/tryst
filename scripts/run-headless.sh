#!/usr/bin/env bash
# TRYST - Headless Server Starter

set -euo pipefail

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "${SCRIPT_DIR}/.."

JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1)"
if [[ "${JAVA_MAJOR}" != "21" && -d /usr/lib/jvm/java-25-openjdk-amd64 ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    export PATH="${JAVA_HOME}/bin:${PATH}"
    JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1)"
fi
if [[ "${JAVA_MAJOR}" != "21" ]]; then
    echo "ERROR: JDK 25 is required. Set JAVA_HOME to a JDK 25 installation." >&2
    exit 1
fi

PRODUCT_DIR="product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64"
LAUNCHER="${PRODUCT_DIR}/tryst"

if [[ ! -x "${LAUNCHER}" ]]; then
    echo "TRYST product not found: ${LAUNCHER}" >&2
    echo "Run scripts/build.sh first." >&2
    exit 1
fi

exec "${LAUNCHER}" -nosplash -consoleLog -application eu.kalafatic.tryst.application.server "$@"
