# Plano de Migração — Eficiência e Suporte HTTP/2

## Estado Atual

| Leg | Componente | Modelo de I/O |
|---|---|---|
| Inbound (cliente → bridge) | `camel-quarkus-platform-http` + Vert.x | Async / non-blocking |
| Outbound (bridge → backend) | `camel-quarkus-vertx-http` + Vert.x WebClient | Async / non-blocking ✅ |

Ambos os lados operam no event loop do Vert.x. A Fase 1 está concluída e validada.

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
void configure(@Observes final BeforeConfigure event) {
    final VertxHttpComponent component = event.getCamelContext()
            .getComponent("vertx-http", VertxHttpComponent.class);
    component.setWebClientOptions(
            new WebClientOptions()
                    .setTrustAll(true)
                    .setVerifyHost(false)
                    .setMaxPoolSize(25)
    );
    component.setVertxHttpBinding(new ProxyVertxHttpBinding());
}
```

Toda a boilerplate de `SSLContext`/`TrustManager`/`PoolingHttpClientConnectionManagerBuilder` foi removida.

#### `ProxyVertxHttpBinding` — classe nova

Resolve o vazamento de headers de requisição na resposta ao cliente. O `DefaultVertxHttpBinding.populateResponseHeaders()` mescla headers do backend no exchange existente, que ainda contém os headers de entrada. A subclasse limpa o exchange antes de delegar ao `super`:

```java
@Override
public void populateResponseHeaders(Exchange exchange,
                                    HttpResponse<Buffer> response,
                                    HeaderFilterStrategy strategy) {
    exchange.getMessage().removeHeaders("*");
    super.populateResponseHeaders(exchange, response, strategy);
}
```

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

#### `ResponseHeaderProcessor` — sem alteração

Continua removendo `Camel*` headers, preservando `CamelHttpResponseCode`. Com o `ProxyVertxHttpBinding`, o exchange após o `.to()` contém apenas headers da resposta do backend — não há mais headers de entrada para limpar.

### Comportamento de headers

| Headers de entrada | Para o backend | Para a resposta ao cliente |
|---|---|---|
| `Camel*` internos | ✗ filtrados pelo `DefaultHeaderFilterStrategy` | ✗ removidos pelo `ResponseHeaderProcessor` |
| `Host`, `*`, `Content-Length`, `Transfer-Encoding` | ✗ removidos antes do `.to()` | ✗ |
| Headers de aplicação (incluindo DN) | ✅ encaminhados | ✗ removidos pelo `ProxyVertxHttpBinding` |
| Headers da resposta do backend | — | ✅ repassados ao cliente |

### Resultado do event loop

```
event loop thread
    │  recebe inbound (platform-http / Vert.x)
    │  chama backend (vertx-http — non-blocking)  ──── registra callback
    │  libera o thread para outras requisições
    │  ...
    │  callback: backend respondeu
    │  ProxyVertxHttpBinding isola headers da resposta
    │  ResponseHeaderProcessor remove Camel* headers
    │  envia resposta ao cliente
```

### Validação (2026-06-05)

| Cenário | Resultado |
|---|---|
| 50 requisições concorrentes → `http://example.com` | 50/50 HTTP 200 em 834 ms |
| 50 requisições concorrentes → `https://httpbin.org` | 50/50 HTTP 200 em 1743 ms |
| Headers de aplicação chegam ao backend | ✅ confirmado via httpbin echo |
| Nenhum header de entrada vaza na resposta | ✅ confirmado via logs do pipeline |
| Camel* headers não chegam ao backend | ✅ confirmado |
| Chave `*` não enviada ao backend | ✅ confirmado |

---

## Fase 2 — Suporte HTTP/2 ponta-a-ponta

**Objetivo:** habilitar HTTP/2 no leg de entrada (cliente → bridge) e no leg de saída (bridge → backend), com degradação automática para HTTP/1.1 conforme a capacidade de cada backend.

**Pré-requisito:** Fase 1 estável em produção.

### 2.1 Inbound — habilitar HTTP/2 no servidor Quarkus/Vert.x

```properties
# application.properties
quarkus.http.http2=true
```

Com isso, o servidor Vert.x anuncia `["h2", "http/1.1"]` via ALPN no handshake TLS. Clientes HTTP/1.1 continuam funcionando sem alteração.

### 2.2 Outbound — ALPN no cliente Vert.x HTTP

```java
options.setProtocolVersion(HttpVersion.HTTP_2)
       .setHttp2ClearTextUpgrade(false)  // não tenta upgrade h2c em conexões plain HTTP
       .setUseAlpn(true);
```

Com `setUseAlpn(true)`, o cliente negocia o protocolo com o backend durante o handshake TLS:
- Backend suporta h2 → HTTP/2
- Backend suporta apenas http/1.1 → HTTP/1.1 (fallback automático)
- Backend é plain HTTP (sem TLS) → HTTP/1.1 (ALPN não se aplica)

**Importante:** nunca usar `setProtocolVersion(HTTP_2)` sem `setUseAlpn(true)` — quebra backends HTTP/1.1-only.

### 2.3 Comportamento por tipo de backend

| Tipo de backend | Protocolo negociado | Ação requerida |
|---|---|---|
| HTTPS com suporte a h2 | HTTP/2 via ALPN | Nenhuma (automático) |
| HTTPS sem suporte a h2 | HTTP/1.1 via ALPN fallback | Nenhuma (automático) |
| HTTP plain (sem TLS) | HTTP/1.1 | Nenhuma (h2c fora do escopo) |

### 2.4 Consideração: multiplexação e pool de conexões

Com HTTP/1.1, cada conexão serve uma requisição → 25 conexões = 25 requisições simultâneas.
Com HTTP/2, uma conexão multiplexa N streams → throughput efetivo por conexão aumenta.

O pool de 25 conexões permanece válido como limite de conexões abertas. O valor de streams por conexão é configurável via `setHttp2MaxPoolSize` no Vert.x — deve ser ajustado para o contexto de proxy com carga concorrente.

### 2.5 Inbound — combinação mTLS + HTTP/2

A combinação `client-auth=REQUIRED` com `h2` via ALPN é suportada no Vert.x 4.x, mas deve ser validada com `curl --http2 --cert ... --key ...` na porta 9443 antes de promover para produção.

---

## Critérios de validação por fase

### Fase 1 ✅

- [x] `curl --http1.1` funciona (regressão zero)
- [x] Métodos GET, POST são corretamente encaminhados ao backend
- [x] Path original é preservado no repasse ao backend
- [x] Headers de aplicação de entrada chegam ao backend
- [x] Headers internos do Camel não vazam para o backend nem para a resposta ao cliente
- [x] Backend com certificado auto-assinado aceita conexão (trust-all ativo)
- [x] `/q/health/live` e `/q/health/ready` respondem na porta 9000

### Fase 2

- [ ] `curl --http2` negocia h2 com a bridge (ALPN inbound)
- [ ] `curl --http1.1` continua funcionando após habilitar HTTP/2
- [ ] Bridge conecta a backend h2-capable usando HTTP/2
- [ ] Bridge conecta a backend HTTP/1.1-only usando HTTP/1.1 (sem falha)
- [ ] Bridge conecta a backend HTTP plain (sem TLS) usando HTTP/1.1
- [ ] Teste end-to-end com Ingress TLS passthrough no OpenShift Local (CRC)
