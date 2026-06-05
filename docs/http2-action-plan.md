# Plano de Ação — Suporte HTTP/2 Ponta-a-Ponta

## Contexto

Em produção, a bridge é exposta via **Ingress OpenShift com TLS passthrough**: o tráfego TLS chega diretamente ao pod sem terminação intermediária. Testes com `curl` demonstraram que conexões com `--http2` falham, funcionando somente com `--http1.1`.

---

## Diagnóstico

### Leg 1 — Entrada: cliente → bridge (porta 9443)

**Causa: `quarkus.http.http2` não declarado em `application.properties`.**

O Quarkus 3.x define `true` como valor padrão para esse parâmetro, mas esse padrão é aplicado antes da resolução da configuração nomeada de TLS (`quarkus.http.tls-configuration-name=mtls-server`). A ausência de declaração explícita gera incerteza sobre se o Vert.x HTTP server anuncia `h2` na extensão **ALPN** (Application-Layer Protocol Negotiation) durante o handshake TLS.

O ALPN é o mecanismo pelo qual servidor e cliente negociam o protocolo de aplicação dentro do handshake TLS. Para que HTTP/2 seja aceito, o servidor deve incluir `"h2"` na lista de protocolos suportados na resposta ALPN. Sem isso, `curl --http2` não consegue estabelecer a sessão e retorna erro — o comportamento **não é** um downgrade silencioso para HTTP/1.1.

**Arquivo afetado:** [`src/main/resources/application.properties`](../src/main/resources/application.properties)

**Correção:** declarar explicitamente `quarkus.http.http2=true`.

---

### Leg 2 — Saída: bridge → backend

**Causa: `HttpComponentCustomizer` usa o gerenciador de conexões síncrono clássico do Apache HttpClient 5, que suporta apenas HTTP/1.1.**

O `PoolingHttpClientConnectionManagerBuilder` cria um `PoolingHttpClientConnectionManager` — a implementação blocking/síncrona do HttpClient 5. O suporte a HTTP/2 nessa biblioteca existe exclusivamente via cliente **assíncrono** (`CloseableHttpAsyncClient` + `PoolingAsyncClientConnectionManager`), que não é o que o componente `camel-quarkus-http` (baseado em Apache HttpClient 5 clássico) expõe.

Independente do protocolo negociado na entrada, a perna bridge → backend sempre será HTTP/1.1.

**Arquivo afetado:** [`src/main/java/com/example/poc/bridge/component/HttpComponentCustomizer.java`](../src/main/java/com/example/poc/bridge/component/HttpComponentCustomizer.java)

**Correção:** substituir `camel-quarkus-http` por `camel-quarkus-vertx-http`, que utiliza o cliente HTTP Vert.x com suporte nativo a HTTP/2 via ALPN.

---

## Resumo das Causas

| Leg | Componente afetado | Causa raiz | Severidade |
|---|---|---|---|
| Entrada (cliente → bridge) | `application.properties` | `quarkus.http.http2` ausente — ALPN pode não anunciar `h2` | Alta — bloqueia HTTP/2 já na conexão TLS |
| Saída (bridge → backend) | `HttpComponentCustomizer` | `PoolingHttpClientConnectionManagerBuilder` é HTTP/1.1 only | Alta — impede HTTP/2 end-to-end mesmo com entrada corrigida |

---

## Etapas do Plano de Ação

### Etapa 1 — Habilitar HTTP/2 na entrada (Quarkus / Vert.x)

- Adicionar `quarkus.http.http2=true` em `application.properties`.
- Verificar se o Quarkus TLS registry propaga os protocolos ALPN (`h2`, `http/1.1`) corretamente para a configuração nomeada `mtls-server`.

### Etapa 2 — Migrar o componente HTTP de saída para Vert.x

- Remover a dependência `camel-quarkus-http` do `pom.xml`.
- Adicionar a dependência `camel-quarkus-vertx-http`.
- Remover ou reescrever `HttpComponentCustomizer`: o cliente Vert.x HTTP suporta HTTP/2 nativamente via ALPN e não requer configuração de `PoolingHttpClientConnectionManager`.
- Avaliar como replicar o comportamento trust-all atual para o cliente Vert.x (necessário para backends com certificados auto-assinados em ambientes internos).

### Etapa 3 — Ajustar a rota Camel

- Revisar a URI de destino na `BridgeRoute`: o componente `vertx-http` usa o esquema `vertx-http:` / `vertx-https:` em vez de `http:` / `https:`.
- Confirmar que as opções `bridgeEndpoint=true` e `throwExceptionOnFailure=false` têm equivalentes no componente Vert.x.
- Verificar tratamento de headers HTTP/2 (pseudo-headers `:method`, `:path`, `:scheme`, `:authority` são transparentes no Vert.x, mas convém validar).

### Etapa 4 — Validação

- Testes com `curl --http2` direto na porta 9443 (sem Ingress) para validar a entrada.
- Testes end-to-end com Ingress TLS passthrough no OpenShift Local (CRC).
- Verificar métricas Prometheus e health endpoints após a migração.
- Atualizar `Containerfile` e `Containerfile.ocp` se necessário.

---

## Compatibilidade com HTTP/1.1

### Leg de entrada — `quarkus.http.http2=true`

Adicionar essa propriedade faz o Vert.x anunciar `["h2", "http/1.1"]` via ALPN no handshake TLS. A negociação é automática:

| Cliente | Resultado |
|---|---|
| Suporta h2, envia ALPN com `h2` | Negocia HTTP/2 |
| Suporta apenas http/1.1 | Negocia HTTP/1.1 |
| Não envia ALPN (clientes antigos) | Tratado como HTTP/1.1 |

**Resultado: nenhum cliente HTTP/1.1 existente é quebrado.**

### Leg de saída — `camel-quarkus-vertx-http`

O cliente Vert.x HTTP suporta ambos os protocolos. A compatibilidade depende do modo de configuração:

| Modo | Backend HTTP/1.1-only |
|---|---|
| ALPN habilitado (h2 + http/1.1 anunciados) | Negocia http/1.1 automaticamente — **compatível** |
| HTTP/2 forçado (sem ALPN) | Conexão falha — **incompatível** |

**Requisito:** a migração deve configurar o cliente Vert.x via ALPN, não via `httpVersion=HTTP_2` forçado. O protocolo deve ser acordado dinamicamente com cada backend.

---

## Riscos e Considerações

| Item | Detalhe |
|---|---|
| HTTP/2 forçado vs. ALPN | Configurar `httpVersion=HTTP_2` sem ALPN no cliente Vert.x quebra backends HTTP/1.1-only. A migração **deve** usar ALPN para negociação automática. |
| `removeHeader("*")` remove `CamelHttpMethod` | Já ocorre hoje — a rota remove todos os headers antes do envio, incluindo o método HTTP. O Camel cai para GET se o header estiver ausente. Não é regressão da migração, mas deve ser corrigido junto. |
| Trust-all SSL | `HttpComponentCustomizer` usa `SSLContext` trust-all (Apache HttpClient 5 API). No Vert.x HTTP client o equivalente é `options.setTrustAll(true)` + `options.setVerifyHost(false)` — semântica igual, API diferente. |
| `setCopyHeaders(false)` | Opção existente no `HttpComponent`; o `VertxHttpComponent` tem opção `copyHeaders`, mas o comportamento padrão precisa ser validado para garantir equivalência. |
| `bridgeEndpoint=true` | Esse parâmetro desabilita reescrita de URL no componente HTTP. O equivalente no Vert.x HTTP precisa ser confirmado. |
| ALPN + mTLS simultâneos | A combinação de `client-auth=REQUIRED` com `h2` via ALPN precisa ser validada no Vert.x — há casos documentados de incompatibilidade em versões antigas do Netty/Vert.x. |
| Mudança de componente Camel | `camel-quarkus-http` → `camel-quarkus-vertx-http` é troca de componente, não de configuração. Requer testes de regressão no comportamento de proxy. |
