#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CTX="${OCP_CONTEXT:-$(oc config current-context)}"
NS="${OCP_NAMESPACE:-poc-mtls-bridge-app}"
BUILD_NAME="mtls-bridge"
APP_NAME="bridge-app"
REPLICAS="${BRIDGE_REPLICAS:-2}"

# Caminhos locais dos PEMs usados para criar/atualizar o Secret no cluster
SERVER_CERT="${BRIDGE_SERVER_CERT_PEM:-$PROJECT_ROOT/infra/certs/server.crt}"
SERVER_KEY="${BRIDGE_SERVER_KEY_PEM:-$PROJECT_ROOT/infra/certs/server.key}"
CLIENT_TRUST="${BRIDGE_CLIENT_TRUST_PEM:-$PROJECT_ROOT/infra/certs/ca-client.crt}"

TARGET_URL="${BRIDGE_TARGET_URL:-https://backend-service:8443}"
DN_HEADER="${BRIDGE_DN_HEADER_NAME:-x-cert-client-subject-dn}"
# Hostname alinhado ao SAN do certificado servidor gerado em infra/certs/server-ext.cnf
ROUTE_HOST="${BRIDGE_ROUTE_HOST:-bridge-app-bridge-poc-app.apps-crc.testing}"

echo "[ocp] [$APP_NAME] context=$CTX namespace=$NS replicas=$REPLICAS target=$TARGET_URL"

# 0. Namespace — cria se não existir
if ! oc --context "${CTX}" get namespace "${NS}" &>/dev/null; then
  oc --context "${CTX}" new-project "${NS}"
  echo "[ocp] [$APP_NAME] Namespace ${NS} created"
else
  echo "[ocp] [$APP_NAME] Namespace ${NS} already exists"
fi

# 1. Compile — produces target/*-runner.jar consumed by Containerfile.ocp
cd "$PROJECT_ROOT"
mvn clean package -DskipTests

# 2. Create ImageStream + BuildConfig (once)
if ! oc --context "${CTX}" -n "${NS}" get buildconfig "${BUILD_NAME}" &>/dev/null; then
  oc --context "${CTX}" -n "${NS}" apply -f - <<YAML
apiVersion: image.openshift.io/v1
kind: ImageStream
metadata:
  name: ${BUILD_NAME}
---
apiVersion: build.openshift.io/v1
kind: BuildConfig
metadata:
  name: ${BUILD_NAME}
spec:
  source:
    type: Binary
    binary: {}
  strategy:
    type: Docker
    dockerStrategy:
      dockerfilePath: Containerfile.ocp
  output:
    to:
      kind: ImageStreamTag
      name: "${BUILD_NAME}:latest"
YAML
  echo "[ocp] [$APP_NAME] BuildConfig created"
else
  oc --context "${CTX}" -n "${NS}" patch buildconfig "${BUILD_NAME}" \
    --type=merge \
    -p '{"spec":{"strategy":{"dockerStrategy":{"dockerfilePath":"Containerfile.ocp"}}}}'
fi

# 3. Build image inside the cluster from local sources
oc --context "${CTX}" -n "${NS}" start-build "${BUILD_NAME}" \
  --from-dir="${PROJECT_ROOT}" \
  --follow

# 4. TLS Secret — cria ou substitui com os PEMs locais
oc --context "${CTX}" -n "${NS}" create secret generic bridge-tls-certs \
  --from-file=server.crt="${SERVER_CERT}" \
  --from-file=server.key="${SERVER_KEY}" \
  --from-file=ca-client.crt="${CLIENT_TRUST}" \
  --dry-run=client -o yaml \
  | oc --context "${CTX}" -n "${NS}" apply -f -

# 5. Deploy (once) — guarda verifica deployment E service para evitar falha parcial do new-app
if ! oc --context "${CTX}" -n "${NS}" get deployment "${APP_NAME}" &>/dev/null && \
   ! oc --context "${CTX}" -n "${NS}" get service   "${APP_NAME}" &>/dev/null; then
  oc --context "${CTX}" -n "${NS}" new-app "${BUILD_NAME}:latest" --name="${APP_NAME}"
  echo "[ocp] [$APP_NAME] Deployment created"
fi

# 6. Monta o Secret de TLS em /deployments/certs (idempotente via --overwrite)
# MSYS_NO_PATHCONV=1 impede Git Bash de converter --mount-path (path de container) para path Windows
MSYS_NO_PATHCONV=1 oc --context "${CTX}" -n "${NS}" set volume deployment/"${APP_NAME}" \
  --add --name=tls-certs \
  --type=secret \
  --secret-name=bridge-tls-certs \
  --mount-path=/deployments/certs \
  --overwrite

# 7. Inject runtime configuration
oc --context "${CTX}" -n "${NS}" set env deployment/"${APP_NAME}" \
  BRIDGE_TARGET_URL="${TARGET_URL}" \
  BRIDGE_DN_HEADER_NAME="${DN_HEADER}" \
  QUARKUS_LOG_CATEGORY__COM_EXAMPLE_POC_BRIDGE__LEVEL=DEBUG

# 8. QoS Guaranteed: requests = limits
CONTAINER=$(oc --context "${CTX}" -n "${NS}" get deployment "${APP_NAME}" \
  -o jsonpath='{.spec.template.spec.containers[0].name}' 2>/dev/null || echo "${APP_NAME}")

oc --context "${CTX}" -n "${NS}" set resources deployment/"${APP_NAME}" \
  --containers="${CONTAINER}" \
  --requests=memory=512Mi,cpu=100m \
  --limits=memory=512Mi,cpu=1000m

# 9. Health probes — management na porta 9000
oc --context "${CTX}" -n "${NS}" set probe deployment/"${APP_NAME}" \
  --liveness \
  --get-url=http://:9000/q/health/live \
  --initial-delay-seconds=30 \
  --period-seconds=10 \
  --failure-threshold=3

oc --context "${CTX}" -n "${NS}" set probe deployment/"${APP_NAME}" \
  --readiness \
  --get-url=http://:9000/q/health/ready \
  --initial-delay-seconds=20 \
  --period-seconds=10 \
  --failure-threshold=3

# 10. Rolling update sem indisponibilidade
oc --context "${CTX}" -n "${NS}" patch deployment/"${APP_NAME}" \
  --type=merge \
  -p '{"spec":{"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxUnavailable":0,"maxSurge":1}}}}'

# 11. Pod Disruption Budget — garante mínimo de 1 pod disponível com 2 réplicas
oc --context "${CTX}" -n "${NS}" apply -f - <<YAML
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: ${APP_NAME}-pdb
spec:
  minAvailable: 1
  selector:
    matchLabels:
      deployment: ${APP_NAME}
YAML

# 12. Escala
oc --context "${CTX}" -n "${NS}" scale deployment/"${APP_NAME}" --replicas="${REPLICAS}"

oc --context "${CTX}" -n "${NS}" rollout status deployment/"${APP_NAME}" --timeout=300s

# 13. Route TLS passthrough — a aplicação termina o TLS; hostname alinhado ao SAN do certificado servidor
if ! oc --context "${CTX}" -n "${NS}" get route "${APP_NAME}" &>/dev/null; then
  oc --context "${CTX}" -n "${NS}" create route passthrough "${APP_NAME}" \
    --service="${APP_NAME}" \
    --port=9443 \
    --hostname="${ROUTE_HOST}"
  echo "[ocp] [$APP_NAME] Route created: https://${ROUTE_HOST}"
else
  echo "[ocp] [$APP_NAME] Route already exists: https://${ROUTE_HOST}"
fi

echo "[ocp] [$APP_NAME] ready — ${REPLICAS} replica(s)"
