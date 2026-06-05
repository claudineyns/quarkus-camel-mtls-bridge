# quarkus-camel-mtls-bridge

## Visão Geral

PoC de **bridge/proxy reverso mTLS** construída com Quarkus 3.27.3 (Red Hat) e Apache Camel. Atua como ponto de entrada TLS mútuo: autentica clientes via certificado X.509, extrai o Distinguished Name (DN) do certificado e o repassa como header HTTP para o backend de destino.

Ambos os lados do pipeline (inbound e outbound) operam no event loop do Vert.x — modelo **async/non-blocking** end-to-end.

## Stack

| Tecnologia | Versão / Detalhe |
|---|---|
| Java | 21 |
| Quarkus | 3.27.3 Red Hat (`quarkus-camel-bom`) |
| Apache Camel | `camel-quarkus-platform-http` (inbound) + `camel-quarkus-vertx-http` (outbound) |
| TLS | `quarkus-tls-registry` |
| Observabilidade | `quarkus-smallrye-health` + `quarkus-micrometer-registry-prometheus` |
| Empacotamento | uber-jar via `quarkus-maven-plugin` |
| Runtime container | `eclipse-temurin:21-jre-alpine` |

## Arquitetura

```
                       ┌────────────────────────────────────────────────────┐
                       │              quarkus-camel-mtls-bridge              │
                       │                                                      │
  Cliente mTLS  ──────►│  :9443 (HTTPS, client-auth=REQUIRED)                │
  (cert X.509)         │     │                                                │
                       │     ▼                                                │
                       │  SslDnFilter  (Vert.x handler, ordem MIN_VALUE)      │
                       │   extrai DN do cert → header x-cert-client-dn        │
                       │     │                                                │
                       │     ▼                                                │
                       │  BridgeRoute (Camel)                                 │
                       │   1. remove headers de controle (Host, *, etc.)      │
                       │   2. encaminha headers de aplicação ao backend        │
                       │   3. chama backend (vertx-http — non-blocking)  ─────┼──────► Backend
                       │   4. ProxyVertxHttpBinding isola resposta do backend  │
                       │   5. ResponseHeaderProcessor (remove Camel* headers)  │
                       │   ↕ IOException → ConnectivityErrorProcessor          │
                       │                  (HTTP 502 + problem+json)            │
                       │                                                      │
                       │  :9000 (management — /q/health, /q/metrics)          │
                       └────────────────────────────────────────────────────┘
```

## Modelo de I/O

| Leg | Componente | Modelo |
|---|---|---|
| Cliente → Bridge | `camel-quarkus-platform-http` + Vert.x | Async / non-blocking |
| Bridge → Backend | `camel-quarkus-vertx-http` + Vert.x WebClient | Async / non-blocking |

## Componentes

### [`BridgeRoute`](../src/main/java/com/example/poc/bridge/route/BridgeRoute.java)

Rota Camel central. Recebe todas as requisições via `platform-http`, remove headers que não devem ser encaminhados ao backend, delega ao backend via `bridge.target.url` e trata `IOException` como erro de conectividade (502).

Headers removidos antes do `.to()`:

| Header | Motivo |
|---|---|
| `CamelHttpUri` | `platform-http` define como path relativo; `vertx-http` o usaria como URL alvo, causando `MalformedURLException` |
| `Host` | Contém o endereço da bridge (ex: `localhost:9696`); o Vert.x reconstrói com o host do backend |
| `*` | Artefato do `matchOnUriPrefix=true` — chave `*` não é token HTTP válido (RFC 7230); causa `400` em servidores estritos |
| `Content-Length` | Vert.x recalcula a partir do body real; manter o original pode criar conflito com `Transfer-Encoding: chunked` |
| `Transfer-Encoding` | Idem — Vert.x define o encoding correto |

Headers de aplicação (incluindo o DN do certificado) são **encaminhados** ao backend sem alteração.

### [`ProxyVertxHttpBinding`](../src/main/java/com/example/poc/bridge/component/ProxyVertxHttpBinding.java)

Extensão de `DefaultVertxHttpBinding`. Sobrescreve `populateResponseHeaders()` para limpar o exchange antes de populá-lo com a resposta do backend, evitando que headers da requisição de entrada (ex: `Accept`, `User-Agent`, headers de aplicação) vazem para a resposta enviada ao cliente.

Injetado no componente via `HttpComponentCustomizer`.

### [`SslDnFilter`](../src/main/java/com/example/poc/bridge/filter/SslDnFilter.java)

Handler Vert.x registrado na ordem `Integer.MIN_VALUE` (antes de qualquer outro). Lê a sessão TLS, extrai o DN do primeiro certificado cliente (`X509Certificate.getSubjectX500Principal().getName()`) e o injeta como header configurável (padrão: `x-cert-client-subject-dn`).

### [`HttpComponentCustomizer`](../src/main/java/com/example/poc/bridge/component/HttpComponentCustomizer.java)

Customiza o `VertxHttpComponent` antes da configuração das rotas via `@Observes BeforeConfigure`:
- `WebClientOptions` com `setTrustAll(true)` + `setVerifyHost(false)` — trust-all para backends com certificados auto-assinados
- `setMaxPoolSize(25)` — pool de conexões por host:porta
- Injeta `ProxyVertxHttpBinding` para controle de headers na resposta

### [`ConnectivityErrorProcessor`](../src/main/java/com/example/poc/bridge/route/processor/ConnectivityErrorProcessor.java)

Tratador de `IOException`. Retorna resposta no formato **RFC 7807** (`application/problem+json`) com `status: 502` e detalhes do erro.

### [`ResponseHeaderProcessor`](../src/main/java/com/example/poc/bridge/route/processor/ResponseHeaderProcessor.java)

Remove headers internos do Camel (`Camel*`) da resposta, preservando `CamelHttpResponseCode` para que o Quarkus envie o status HTTP correto ao cliente.

### [`BridgeConfig`](../src/main/java/com/example/poc/bridge/config/BridgeConfig.java)

Interface `@ConfigMapping` que expõe:
- `bridge.target.url` — URL do backend de destino
- `bridge.dn.header-name` — nome do header onde o DN será injetado (padrão: `x-cert-client-subject-dn`)

## Configuração

### `application.properties` (valores padrão)

```properties
# Identidade TLS do servidor (inbound)
quarkus.tls.mtls-server.key-store.pem.0.cert=${BRIDGE_SERVER_CERT_PEM:certs/server.crt}
quarkus.tls.mtls-server.key-store.pem.0.key=${BRIDGE_SERVER_KEY_PEM:certs/server.key}

# CA que assinou os certificados dos clientes (inbound)
quarkus.tls.mtls-server.trust-store.pem.certs=${BRIDGE_CLIENT_TRUST_PEM:certs/ca-client.crt}

# Servidor HTTP
quarkus.http.tls-configuration-name=mtls-server
quarkus.http.ssl.client-auth=REQUIRED
quarkus.http.insecure-requests=disabled
quarkus.http.port=8080
quarkus.http.ssl-port=9443

# Bridge
bridge.target.url=https://localhost:9443
bridge.dn.header-name=x-cert-client-subject-dn

# Management
quarkus.management.enabled=true
quarkus.management.port=9000
```

### Variáveis de Ambiente

| Variável | Descrição | Padrão |
|---|---|---|
| `BRIDGE_SERVER_CERT_PEM` | Caminho do certificado PEM do servidor | `certs/server.crt` |
| `BRIDGE_SERVER_KEY_PEM` | Caminho da chave privada PEM do servidor | `certs/server.key` |
| `BRIDGE_CLIENT_TRUST_PEM` | Caminho da CA que valida certificados de clientes | `certs/ca-client.crt` |
| `BRIDGE_TARGET_URL` | URL do backend de destino | `https://localhost:9443` |
| `BRIDGE_DN_HEADER_NAME` | Nome do header que receberá o DN do certificado | `x-cert-client-subject-dn` |

## Infraestrutura

### Certificados (`infra/certs/`)

Certificados de teste gerados localmente:
- `ca.crt` / `ca.key` — CA raiz
- `server.crt` / `server.key` — certificado do servidor (SAN: `localhost`, `127.0.0.1`, `bridge-app`, `bridge-app-bridge-poc-app.apps-crc.testing`)
- `ca-client.crt` — CA que assina certificados de clientes
- `client.crt` / `client.key` — certificado de cliente para testes

### Container (`Containerfile`)

```
eclipse-temurin:21-jre-alpine
WORKDIR /deployments
EXPOSE 9443 9000
JVM: -Xms256m -Xmx512m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m
```

### Execução com Podman (`infra/start-podman.sh`)

```bash
# Execução básica (usa valores padrão)
bash infra/start-podman.sh

# Com backend customizado
BRIDGE_TARGET_URL=https://meu-backend:8443 bash infra/start-podman.sh
```

O script realiza: `mvn clean package` → `podman build` → `podman run` montando o diretório de certs como volume read-only.

Portas mapeadas pelo script:
- `9696 → 9443` (HTTPS/mTLS)
- `9000 → 9000` (management)

### OpenShift Local (CRC)

O SAN do certificado servidor inclui `bridge-app-bridge-poc-app.apps-crc.testing`, indicando suporte a deploy em **OpenShift Local**. O arquivo `Containerfile.ocp` contém a variante de imagem para esse ambiente.

## Observabilidade

| Endpoint | Porta | Descrição |
|---|---|---|
| `/q/health/live` | 9000 | Liveness probe |
| `/q/health/ready` | 9000 | Readiness probe |
| `/q/metrics` | 9000 | Métricas Prometheus |

## Limitações Conhecidas

- O `HttpComponentCustomizer` usa `setTrustAll(true)` + `setVerifyHost(false)` para conexões de saída — adequado para redes internas/controladas, não recomendado para produção sem ajuste.
- Não há testes automatizados implementados.
- HTTP/2 não está habilitado (planejado na Fase 2 — ver `migration-plan.md`).
