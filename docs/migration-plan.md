# Plano de Migração — Eficiência e Suporte HTTP/2

## Estado Atual — v0.1.0

| Leg | Componente | Modelo de I/O | Protocolo |
|---|---|---|---|
| Inbound (cliente → bridge) | `camel-quarkus-platform-http` + Vert.x | Async / non-blocking | HTTP/1.1 ou HTTP/2 via ALPN |
| Outbound (bridge → backend) | `camel-quarkus-vertx-http` + Vert.x WebClient | Async / non-blocking | HTTP/2 via ALPN (HTTPS) / HTTP/1.1 (plain HTTP) |

Ambas as fases foram concluídas e validadas. O modelo é fully async/non-blocking end-to-end com suporte HTTP/2 ponta-a-ponta.

---

## Fase 1 — Migração para `camel-quarkus-vertx-http` ✅ CONCLUÍDA

**Objetivo:** eliminar o modelo bloqueante no leg bridge → backend (Apache HttpClient 5), alinhando ambos os lados no event loop do Vert.x.

### Alterações implementadas

#### `pom.xml`

```xml
<!-- removido -->
<dependency>
    <groupId>org.apache.camel.quarkus</groupId>
    <artifactId>camel-quarkus-http</artifactId>
</dependency>

<!-- adicionado -->
<dependency>
    <groupId>org.apache.camel.quarkus</groupId>
    <artifactId>camel-quarkus-vertx-http</artifactId>
</dependency>
```

#### `HttpComponentCustomizer` — reescrito

```java
component.setWebClientOptions(
        new WebClientOptions()
                .setTrustAll(true)
                .setVerifyHost(verifyHost)   // bridge.http-client.verify-host
                .setMaxPoolSize(maxPoolSize) // bridge.http-client.max-pool-size
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setUseAlpn(true)
);
component.setVertxHttpBinding(new ProxyVertxHttpBinding());
```

Toda a boilerplate de `SSLContext`/`TrustManager`/`PoolingHttpClientConnectionManagerBuilder` foi removida.

#### `ProxyVertxHttpBinding` — classe nova

Resolve o vazamento de headers de requisição na resposta ao cliente. O `DefaultVertxHttpBinding.populateResponseHeaders()` mescla headers do backend no exchange existente, que ainda contém os headers de entrada. A subclasse limpa o exchange antes de delegar ao `super`:

```java
@Override
public void populateResponseHeaders(Exchange exchange,
                                    HttpResponse<Buffer> response,
                                    HeaderFilterStrategy strategy) {
    LOG.trace("outbound protocol: {} → backend HTTP {}", response.version(), response.statusCode());
    exchange.getMessage().removeHeaders("*");
    super.populateResponseHeaders(exchange, response, strategy);
}
```

Log `TRACE` para diagnóstico de protocolo outbound — silencioso por padrão, ativável via `BRIDGE_HTTP_CLIENT_VERIFY_HOST`.

#### `BridgeRoute` — remoção de headers obrigatórios

Cinco `removeHeader` adicionados antes do `.to()`, cada um com causa específica descoberta em testes:

| Header removido | Causa |
|---|---|
| `CamelHttpUri` (`Exchange.HTTP_URI`) | `platform-http` define como path relativo; `vertx-http` o usa como URL alvo → `MalformedURLException` |
| `Host` | Contém o endereço da bridge; backend recebe host errado → `400 Bad Request` |
| `*` | Artefato do `matchOnUriPrefix=true`; chave `*` é token HTTP inválido (RFC 7230) → `400` em AWS ELB e similares |
| `Content-Length` | Conflito com `Transfer-Encoding: chunked` do Vert.x → `400` em servidores estritos |
| `Transfer-Encoding` | Idem |

**Descartados durante investigação:**
- `copyHeaders=false` (URI parameter) — campo não existe em `VertxHttpConfiguration`, silenciosamente ignorado
- `bridgeEndpoint=true` — corrompe a construção do URL (request-target fica `?copyHeaders=false` em vez do path real)

### Validação da Fase 1

| Cenário | Resultado |
|---|---|
| 50 requisições concorrentes → `http://example.com` | 50/50 HTTP 200 em 834 ms |
| 50 requisições concorrentes → `https://httpbin.org` | 50/50 HTTP 200 em 1743 ms |
| 20 × 6,71 MB POST concorrentes (integridade SHA-256) | 20/20 OK — 134 MB íntegros |
| Headers de aplicação chegam ao backend | ✅ confirmado via httpbin echo |
| Nenhum header de entrada vaza na resposta | ✅ confirmado via logs do pipeline |

---

## Fase 2 — Suporte HTTP/2 ponta-a-ponta ✅ CONCLUÍDA

**Objetivo:** habilitar HTTP/2 no leg de entrada (cliente → bridge) e no leg de saída (bridge → backend), com degradação automática para HTTP/1.1 conforme a capacidade de cada backend.

### Alterações implementadas

#### `application.properties`

```properties
quarkus.http.http2=true
```

Faz o Vert.x anunciar `["h2", "http/1.1"]` via ALPN no handshake TLS. Clientes HTTP/1.1 continuam funcionando sem alteração.

#### `HttpComponentCustomizer` — adições ao `WebClientOptions`

```java
.setProtocolVersion(HttpVersion.HTTP_2)
.setUseAlpn(true)
```

Com `setUseAlpn(true)`, o cliente negocia com o backend no handshake TLS:
- Backend HTTPS com suporte a h2 → HTTP/2 (confirmado: `outbound protocol: HTTP_2`)
- Backend HTTPS sem suporte a h2 → fallback HTTP/1.1 via ALPN
- Backend plain HTTP → fallback HTTP/1.1 via h2c upgrade (confirmado: `outbound protocol: HTTP_1_1`)

`setHttp2ClearTextUpgrade` **não** foi desabilitado: com o default `true`, para backends plain HTTP o cliente tenta upgrade h2c; se o backend responde com 200 (não 101), o cliente continua em HTTP/1.1 automaticamente.

### Validação da Fase 2

| Cenário | Inbound | Outbound | Resultado |
|---|---|---|---|
| `curl --http2` → httpbin (HTTPS h2-capable) | HTTP/2 | HTTP_2 via ALPN | ✅ 200 |
| `curl --http1.1` → httpbin | HTTP/1.1 | HTTP_2 via ALPN | ✅ 200 — sem regressão |
| `curl --http2` → mock (HTTP/1.1-only, plain HTTP) | HTTP/2 | HTTP_1_1 fallback | ✅ 200 |

---

## Comportamento de headers — resumo final

| Headers de entrada | Para o backend | Para a resposta ao cliente |
|---|---|---|
| `Camel*` internos | ✗ filtrados pelo `DefaultHeaderFilterStrategy` | ✗ removidos pelo `ResponseHeaderProcessor` |
| `Host`, `*`, `Content-Length`, `Transfer-Encoding` | ✗ removidos antes do `.to()` | ✗ |
| Headers de aplicação (incluindo DN) | ✅ encaminhados | ✗ removidos pelo `ProxyVertxHttpBinding` |
| Headers da resposta do backend | — | ✅ repassados ao cliente |

---

## Critérios de validação

### Fase 1 ✅

- [x] `curl --http1.1` funciona (regressão zero)
- [x] Métodos GET, POST são corretamente encaminhados ao backend
- [x] Path original é preservado no repasse ao backend
- [x] Headers de aplicação de entrada chegam ao backend
- [x] Headers internos do Camel não vazam para o backend nem para a resposta ao cliente
- [x] Backend com certificado auto-assinado aceita conexão (trust-all ativo)
- [x] `/q/health/live` e `/q/health/ready` respondem na porta 9000
- [x] Transferência de payload grande (6,71 MB) com integridade SHA-256 confirmada

### Fase 2 ✅

- [x] `curl --http2` negocia h2 com a bridge (ALPN inbound)
- [x] `curl --http1.1` continua funcionando após habilitar HTTP/2
- [x] Bridge conecta a backend h2-capable usando HTTP/2
- [x] Bridge conecta a backend HTTP/1.1-only (plain HTTP) usando HTTP/1.1 sem falha
- [ ] Teste end-to-end com Ingress TLS passthrough no OpenShift Local (CRC)
