# quarkus-camel-mtls-bridge

## Visão Geral

Bridge/proxy reverso mTLS construída com Quarkus 3.27.3 (Red Hat) e Apache Camel. Atua como ponto de entrada TLS mútuo: autentica clientes via certificado X.509, extrai o Distinguished Name (DN) do certificado e o repassa como header HTTP para o backend de destino.

Ambos os lados do pipeline (inbound e outbound) operam no event loop do Vert.x — modelo **async/non-blocking** end-to-end, com **HTTP/2 via ALPN** em ambos os legs.

**Versão:** 0.1.0

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
  Cliente mTLS  ──────►│  :9443 (HTTPS, client-auth=REQUIRED, HTTP/2+ALPN)   │
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
                       │   4. ProxyVertxHttpBinding isola resposta do backend  │  (HTTP/2 via ALPN
                       │   5. ResponseHeaderProcessor (remove Camel* headers)  │   ou HTTP/1.1)
                       │   ↕ IOException → ConnectivityErrorProcessor          │
                       │                  (HTTP 502 + problem+json)            │
                       │                                                      │
                       │  :9000 (management — /q/health, /q/metrics)          │
                       └────────────────────────────────────────────────────┘
```

## Modelo de I/O e Protocolo

| Leg | Componente | Modelo | Protocolo |
|---|---|---|---|
| Cliente → Bridge | `camel-quarkus-platform-http` + Vert.x | Async / non-blocking | HTTP/1.1 ou HTTP/2 via ALPN |
| Bridge → Backend | `camel-quarkus-vertx-http` + Vert.x WebClient | Async / non-blocking | HTTP/2 via ALPN (HTTPS) / HTTP/1.1 fallback (plain HTTP) |

## Componentes

### [`BridgeRoute`](../src/main/java/com/example/poc/bridge/route/BridgeRoute.java)

Rota Camel central. Recebe todas as requisições via `platform-http`, remove headers que não devem ser encaminhados ao backend, delega ao backend via `bridge.target.url` e trata `IOException` como erro de conectividade (502).

Headers removidos antes do `.to()`:

| Header | Motivo |
|---|---|
| `CamelHttpUri` | `platform-http` define como path relativo; `vertx-http` o usaria como URL alvo → `MalformedURLException` |
| `Host` | Contém o endereço da bridge; o Vert.x reconstrói com o host do backend |
| `*` | Artefato do `matchOnUriPrefix=true` — chave `*` não é token HTTP válido (RFC 7230) → `400` em servidores estritos |
| `Content-Length` | Vert.x recalcula a partir do body real; conflito com `Transfer-Encoding: chunked` causa `400` |
| `Transfer-Encoding` | Idem |

Três logs INFO permanentes nos pontos INBOUND, BACKEND e OUTBOUND do pipeline para rastreabilidade operacional.

### [`ProxyVertxHttpBinding`](../src/main/java/com/example/poc/bridge/component/ProxyVertxHttpBinding.java)

Extensão de `DefaultVertxHttpBinding`. Sobrescreve `populateResponseHeaders()` para limpar o exchange antes de populá-lo com a resposta do backend, evitando que headers da requisição de entrada vazem para a resposta enviada ao cliente.

Inclui log `TRACE` do protocolo outbound negociado (`HTTP_2` ou `HTTP_1_1`) — silencioso por padrão, ativável via variável de ambiente sem rebuild.

### [`SslDnFilter`](../src/main/java/com/example/poc/bridge/filter/SslDnFilter.java)

Handler Vert.x registrado na ordem `Integer.MIN_VALUE`. Extrai o DN do primeiro certificado cliente e o injeta como header configurável (padrão: `x-cert-client-subject-dn`).

### [`HttpComponentCustomizer`](../src/main/java/com/example/poc/bridge/component/HttpComponentCustomizer.java)

Customiza o `VertxHttpComponent` via `@Observes BeforeConfigure`:

```java
new WebClientOptions()
    .setTrustAll(true)
    .setVerifyHost(verifyHost)    // bridge.http-client.verify-host
    .setMaxPoolSize(maxPoolSize)  // bridge.http-client.max-pool-size
    .setProtocolVersion(HttpVersion.HTTP_2)
    .setUseAlpn(true)
```

- HTTP/2 via ALPN para backends HTTPS; fallback automático para HTTP/1.1
- Para backends plain HTTP: h2c upgrade tentado; se o servidor responde com 200 (não 101), continua em HTTP/1.1

### [`ConnectivityErrorProcessor`](../src/main/java/com/example/poc/bridge/route/processor/ConnectivityErrorProcessor.java)

Tratador de `IOException`. Retorna RFC 7807 (`application/problem+json`) com `status: 502`.

### [`ResponseHeaderProcessor`](../src/main/java/com/example/poc/bridge/route/processor/ResponseHeaderProcessor.java)

Remove headers `Camel*` da resposta, preservando `CamelHttpResponseCode` para que o Quarkus envie o status HTTP correto ao cliente.

### [`BridgeConfig`](../src/main/java/com/example/poc/bridge/config/BridgeConfig.java)

Interface `@ConfigMapping` que expõe `bridge.target.url` e `bridge.dn.header-name`.

## Configuração

### `application.properties` (valores padrão)

```properties
# TLS inbound
quarkus.tls.mtls-server.key-store.pem.0.cert=${BRIDGE_SERVER_CERT_PEM:certs/server.crt}
quarkus.tls.mtls-server.key-store.pem.0.key=${BRIDGE_SERVER_KEY_PEM:certs/server.key}
quarkus.tls.mtls-server.trust-store.pem.certs=${BRIDGE_CLIENT_TRUST_PEM:certs/ca-client.crt}

# Servidor HTTP
quarkus.http.tls-configuration-name=mtls-server
quarkus.http.ssl.client-auth=REQUIRED
quarkus.http.insecure-requests=disabled
quarkus.http.ssl-port=9443
quarkus.http.http2=true

# Bridge
bridge.target.url=https://localhost:9443
bridge.dn.header-name=x-cert-client-subject-dn
bridge.http-client.max-pool-size=25
bridge.http-client.verify-host=false

# Management
quarkus.management.enabled=true
quarkus.management.port=9000

# Diagnóstico (ativar via env para ver protocolo outbound por requisição)
quarkus.log.category."com.example.poc.bridge.component.ProxyVertxHttpBinding".min-level=TRACE
quarkus.log.category."com.example.poc.bridge.component.ProxyVertxHttpBinding".level=INFO
```

### Variáveis de Ambiente

| Variável | Propriedade | Padrão |
|---|---|---|
| `BRIDGE_SERVER_CERT_PEM` | `quarkus.tls.mtls-server.key-store.pem.0.cert` | `certs/server.crt` |
| `BRIDGE_SERVER_KEY_PEM` | `quarkus.tls.mtls-server.key-store.pem.0.key` | `certs/server.key` |
| `BRIDGE_CLIENT_TRUST_PEM` | `quarkus.tls.mtls-server.trust-store.pem.certs` | `certs/ca-client.crt` |
| `BRIDGE_TARGET_URL` | `bridge.target.url` | `https://localhost:9443` |
| `BRIDGE_DN_HEADER_NAME` | `bridge.dn.header-name` | `x-cert-client-subject-dn` |
| `BRIDGE_HTTP_CLIENT_MAX_POOL_SIZE` | `bridge.http-client.max-pool-size` | `25` |
| `BRIDGE_HTTP_CLIENT_VERIFY_HOST` | `bridge.http-client.verify-host` | `false` |

## Infraestrutura

### Execução com Podman (`infra/start-podman.sh`)

```bash
bash infra/start-podman.sh
BRIDGE_TARGET_URL=https://meu-backend:8443 bash infra/start-podman.sh
```

Portas mapeadas: `9696 → 9443` (mTLS), `9000 → 9000` (management).

### OpenShift Local (CRC)

`Containerfile.ocp` contém a variante de imagem para deploy em OpenShift Local. O SAN do certificado inclui `bridge-app-bridge-poc-app.apps-crc.testing`.

## Observabilidade

| Endpoint | Porta | Descrição |
|---|---|---|
| `/q/health/live` | 9000 | Liveness probe |
| `/q/health/ready` | 9000 | Readiness probe |
| `/q/metrics` | 9000 | Métricas Prometheus |

## Limitações Conhecidas

- `setTrustAll(true)` — adequado para redes internas/controladas; em produção com CA conhecida, substituir por configuração de truststore específica via `quarkus-tls-registry`
- Teste end-to-end com Ingress TLS passthrough no OpenShift Local (CRC) ainda não executado
- Não há testes automatizados implementados
