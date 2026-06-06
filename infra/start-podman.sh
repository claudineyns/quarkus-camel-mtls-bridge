#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE_NAME="mtls-bridge:latest"
CONTAINER_NAME="bridge-app"

# Certs esperados em $PROJECT_ROOT/certs/ (montados no caminho default da aplicação)
CERTS_DIR="${BRIDGE_CERTS_DIR:-$PROJECT_ROOT/infra/certs}"

TARGET_URL="${BRIDGE_TARGET_URL:-https://localhost:9443}"
DN_HEADER="${BRIDGE_DN_HEADER_NAME:-x-client-cert-subject-dn}"

echo "[podman] [$CONTAINER_NAME] target=$TARGET_URL"

# 1. Compile and package
cd "$PROJECT_ROOT"
mvn clean package -DskipTests

# 2. Create network
podman network exists mtls-bridge-net || podman network create mtls-bridge-net

# 3. Build image
podman build -t "$IMAGE_NAME" -f Containerfile "$PROJECT_ROOT"

# 4. Start container (idempotente — remove instância anterior se existir)
podman rm -f "$CONTAINER_NAME" 2>/dev/null || true

MSYS_NO_PATHCONV=1 podman run -d \
  --name    "$CONTAINER_NAME" \
  --network mtls-bridge-net \
  -p 9696:9443 \
  -p 9000:9000 \
  --memory  512m \
  -v "${CERTS_DIR}:/deployments/certs:ro" \
  -e BRIDGE_TARGET_URL="${TARGET_URL}" \
  -e BRIDGE_DN_HEADER_NAME="${DN_HEADER}" \
  -e BRIDGE_CERT_ISSUER_DN_HEADER_NAME="${BRIDGE_CERT_ISSUER_DN_HEADER_NAME:-}" \
  -e BRIDGE_CERT_SERIAL_HEADER_NAME="${BRIDGE_CERT_SERIAL_HEADER_NAME:-}" \
  -e BRIDGE_CERT_NOT_BEFORE_HEADER_NAME="${BRIDGE_CERT_NOT_BEFORE_HEADER_NAME:-}" \
  -e BRIDGE_CERT_NOT_AFTER_HEADER_NAME="${BRIDGE_CERT_NOT_AFTER_HEADER_NAME:-}" \
  -e BRIDGE_CERT_FINGERPRINT_HEADER_NAME="${BRIDGE_CERT_FINGERPRINT_HEADER_NAME:-}" \
  -e BRIDGE_CERT_SAN_HEADER_NAME="${BRIDGE_CERT_SAN_HEADER_NAME:-}" \
  -e BRIDGE_CERT_PEM_HEADER_NAME="${BRIDGE_CERT_PEM_HEADER_NAME:-}" \
  "$IMAGE_NAME"

echo "[podman] [$CONTAINER_NAME] started — https://localhost:8443  management: http://localhost:9000/q/health"
