# Plano de Migração — Eficiência e Suporte HTTP/2

## Estado Atual

| Leg | Componente | Modelo de I/O |
|---|---|---|
| Inbound (cliente → bridge) | `camel-quarkus-platform-http` + Vert.x | Async / non-blocking |
| Outbound (bridge → backend) | `camel-quarkus-http` + Apache HttpClient 5 | Síncrono / bloqueante |

O desalinhamento entre os dois lados faz com que cada requisição em voo consuma um worker thread bloqueado aguardando o backend. Sob carga concorrente, o limite prático de paralelismo é o tamanho do pool de conexões (25), independente da capacidade do event loop.

---

## Comportamento de headers — análise e decisão

A intenção original do `removeHeader("*")` era impedir que headers de entrada contaminassem a **resposta** ao cliente no pipeline do Camel — não barrar a passagem ao backend.

O componente `camel-quarkus-vertx-http` torna o `removeHeader` desnecessário por dois mecanismos:

1. **`DefaultHeaderFilterStrategy`** — filtra automaticamente todos os headers cujo nome comece com `Camel*`, impedindo que vazem como headers HTTP ao backend. Não é necessário removê-los manualmente.
2. **Headers de controle consumidos pelo componente** — `CamelHttpMethod`, `CamelHttpPath` e `CamelHttpQuery` são lidos pelo componente para construir a requisição de saída (método, path, query string) e não são enviados como headers HTTP ao backend.
3. **`copyHeaders=false`** — após o `.to()`, o exchange contém apenas os headers da resposta do backend, sem mesclagem com headers de entrada. Isso evita contaminação da resposta sem qualquer remoção manual.

**Consequência:** o `removeHeader("*")` atual impedia que headers de aplicação chegassem ao backend — comportamento incorreto para um proxy. A migração corrige isso naturalmente, sem nenhuma linha adicional na rota.

**Efeito colateral positivo:** com `CamelHttpPath` preservado no exchange, o componente constrói o URL de saída corretamente como `bridge.target.url + path_original`. Requests para `/api/v1/foo` são encaminhados para `https://backend:9443/api/v1/foo` — correção implícita de um bug de repasse de path presente na implementação atual.

---

## Fase 1 — Migração para `camel-quarkus-vertx-http`

**Objetivo:** eliminar o modelo bloqueante no leg bridge → backend, alinhando ambos os lados no event loop do Vert.x. Sem HTTP/2 nesta fase.

### 1.1 `pom.xml`

Substituir:
```xml
<!-- remover -->
<dependency>
    <groupId>org.apache.camel.quarkus</groupId>
    <artifactId>camel-quarkus-http</artifactId>
</dependency>

<!-- adicionar -->
<dependency>
    <groupId>org.apache.camel.quarkus</groupId>
    <artifactId>camel-quarkus-vertx-http</artifactId>
</dependency>
```

### 1.2 `HttpComponentCustomizer` — reescrever para `VertxHttpComponent`

A classe é reescrita mantendo o mesmo padrão (`@Observes BeforeConfigure`), mas agora configura o `VertxHttpComponent` via `WebClientOptions`. Toda a boilerplate de `SSLContext`/`TrustManager`/`PoolingHttpClientConnectionManagerBuilder` é removida.

```java
@ApplicationScoped
public class HttpComponentCustomizer {

    void configure(@Observes final BeforeConfigure event) {
        final VertxHttpComponent component = event.getCamelContext()
                .getComponent("vertx-http", VertxHttpComponent.class);
        component.setWebClientOptions(
                new WebClientOptions()
                        .setTrustAll(true)
                        .setVerifyHost(false)
                        .setMaxPoolSize(25)
        );
    }
}
```

- `setTrustAll(true)` + `setVerifyHost(false)` — equivalente ao `SSLContext` trust-all atual
- `setMaxPoolSize(25)` — equivalente ao `setMaxConnPerRoute(25)` atual (Vert.x aplica por host:porta de destino)
- `setCopyHeaders(false)` é tratado como parâmetro de URI na rota (mais visível)

### 1.3 `application.properties` — sem alterações nesta fase

Toda a configuração do cliente de saída vai para o `HttpComponentCustomizer`. Nenhuma propriedade nova é necessária em `application.properties` para a Fase 1.

### 1.4 `BridgeRoute` — simplificação

```java
from("platform-http:/?matchOnUriPrefix=true")
    .setProperty("bridge.requestPath", header(Exchange.HTTP_PATH))
    .log(LoggingLevel.DEBUG, LOGGER,
            "→ REQ  ${header.CamelHttpMethod} ${header.CamelHttpPath} | headers: ${headers}")
    .to("vertx-http:{{bridge.target.url}}?throwExceptionOnFailure=false&copyHeaders=false")
    .process(responseHeaderProcessor)
    .log(LoggingLevel.DEBUG, LOGGER,
            "← RESP ${header.CamelHttpResponseCode} | headers: ${headers}");
```

Mudanças em relação ao estado atual:
- `removeHeader("*")` removido — o componente filtra `Camel*` automaticamente
- Prefixo `vertx-http:` adicionado ao URI de destino
- `bridgeEndpoint=true` removido — não existe no Vert.x; o comportamento é default
- `copyHeaders=false` adicionado como parâmetro de URI

### 1.5 `ResponseHeaderProcessor` — sem alteração

O comportamento atual já está correto: remove headers `Camel*` preservando `CamelHttpResponseCode`. Com `copyHeaders=false` no componente Vert.x, o exchange após o `.to()` contém apenas os headers da resposta do backend — não há headers de entrada misturados para limpar.

### 1.6 Resultado esperado da Fase 1

```
event loop thread
    │  recebe inbound (platform-http / Vert.x)
    │  chama backend (vertx-http — non-blocking)  ──── registra callback
    │  libera o thread para outras requisições
    │  ...
    │  callback: backend respondeu
    │  processa ResponseHeaderProcessor
    │  envia resposta ao cliente
```

O mesmo thread atende múltiplas requisições em voo sem bloquear. O limite de concorrência deixa de ser o pool de worker threads e passa a ser o pool de conexões ao backend (mantido em 25 como ponto de partida conservador).

---

## Fase 2 — Suporte HTTP/2 ponta-a-ponta

**Objetivo:** habilitar HTTP/2 no leg de entrada (cliente → bridge) e no leg de saída (bridge → backend), com degradação automática para HTTP/1.1 conforme a capacidade de cada backend.

**Pré-requisito:** Fase 1 estável em produção.

### 2.1 Inbound — habilitar HTTP/2 no servidor Quarkus/Vert.x

```properties
# application.properties
quarkus.http.http2=true
```

Com isso, o servidor Vert.x anuncia `["h2", "http/1.1"]` via ALPN no handshake TLS. Clientes HTTP/1.1 continuam funcionando sem alteração — a negociação é automática.

### 2.2 Outbound — ALPN no cliente Vert.x HTTP

O cliente Vert.x HTTP precisa anunciar `["h2", "http/1.1"]` via ALPN para backends HTTPS. A configuração via `WebClientOptions`:

```java
options.setProtocolVersion(HttpVersion.HTTP_2)
       .setHttp2ClearTextUpgrade(false)  // não tenta upgrade h2c em conexões plain HTTP
       .setUseAlpn(true);
```

Com `setUseAlpn(true)`, o cliente negocia o protocolo com o backend durante o handshake TLS:
- Backend suporta h2 → usa HTTP/2
- Backend suporta apenas http/1.1 → usa HTTP/1.1 (fallback automático)
- Backend é plain HTTP (sem TLS) → usa HTTP/1.1 (ALPN não se aplica, h2c fora do escopo)

### 2.3 Comportamento por tipo de backend

| Tipo de backend | Protocolo negociado | Ação requerida |
|---|---|---|
| HTTPS com suporte a h2 | HTTP/2 via ALPN | Nenhuma (automático) |
| HTTPS sem suporte a h2 | HTTP/1.1 via ALPN fallback | Nenhuma (automático) |
| HTTP plain (sem TLS) | HTTP/1.1 | Nenhuma (h2c fora do escopo) |

### 2.4 Consideração: multiplexação e pool de conexões

Com HTTP/1.1, cada conexão serve uma requisição por vez → 25 conexões = 25 requisições simultâneas.  
Com HTTP/2, uma conexão pode multiplexar N streams → 25 conexões × N streams cada.

O pool de 25 conexões permanece válido como limite de conexões abertas ao backend, mas o throughput efetivo por conexão aumenta com HTTP/2. O valor de N (streams por conexão) é configurável via `setHttp2MaxPoolSize` no Vert.x; o default do Vert.x (1 conexão por host para HTTP/2) precisará ser ajustado para o contexto de proxy com carga concorrente.

### 2.5 Inbound — combinação mTLS + HTTP/2

A combinação `client-auth=REQUIRED` (mTLS) com `h2` via ALPN requer que o Vert.x negocie ambos corretamente no mesmo handshake TLS. Esta combinação é suportada no Vert.x 4.x (base do Quarkus 3.x), mas precisa ser validada com teste direto via `curl --http2 --cert ... --key ...` na porta 9443 antes de promover para produção.

---

## Critérios de validação por fase

### Fase 1
- [ ] `curl --http1.1` funciona (regressão zero)
- [ ] Métodos GET, POST, PUT, DELETE, PATCH são corretamente encaminhados ao backend
- [ ] Path original é preservado no repasse ao backend (`/api/v1/foo` → `backend:9443/api/v1/foo`)
- [ ] Query string é preservada no repasse ao backend
- [ ] Headers de aplicação de entrada chegam ao backend
- [ ] Headers internos do Camel não vazam para o backend nem para a resposta ao cliente
- [ ] Backend com certificado auto-assinado aceita conexão (trust-all ativo)
- [ ] `/q/health/live` e `/q/health/ready` respondem na porta 9000

### Fase 2
- [ ] `curl --http2` negocia h2 com a bridge (ALPN inbound)
- [ ] `curl --http1.1` continua funcionando após habilitar HTTP/2
- [ ] Bridge conecta a backend h2-capable usando HTTP/2
- [ ] Bridge conecta a backend HTTP/1.1-only usando HTTP/1.1 (sem falha)
- [ ] Bridge conecta a backend HTTP plain (sem TLS) usando HTTP/1.1
- [ ] Teste end-to-end com Ingress TLS passthrough no OpenShift Local (CRC)
