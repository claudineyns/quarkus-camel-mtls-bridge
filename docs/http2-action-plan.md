# Diagnóstico HTTP/2 — Histórico e Plano de Ação

> **Nota:** este documento registra o diagnóstico original do problema HTTP/2. O plano de ação detalhado e os critérios de validação estão em [`migration-plan.md`](migration-plan.md) (Fase 2).

---

## Contexto

Em produção, a bridge é exposta via **Ingress OpenShift com TLS passthrough**: o tráfego TLS chega diretamente ao pod sem terminação intermediária. Testes com `curl` demonstraram que conexões com `--http2` falham, funcionando somente com `--http1.1`.

---

## Diagnóstico

### Leg 1 — Entrada: cliente → bridge (porta 9443)

**Causa: `quarkus.http.http2` não declarado em `application.properties`.**

O Quarkus 3.x define `true` como valor padrão, mas esse padrão é aplicado antes da resolução da configuração nomeada de TLS (`quarkus.http.tls-configuration-name=mtls-server`). A ausência de declaração explícita gera incerteza sobre se o Vert.x HTTP server anuncia `h2` na extensão **ALPN** durante o handshake TLS.

**Correção planejada:** declarar explicitamente `quarkus.http.http2=true` na Fase 2.

---

### Leg 2 — Saída: bridge → backend

**Causa original (resolvida na Fase 1):** `HttpComponentCustomizer` usava `PoolingHttpClientConnectionManagerBuilder` do Apache HttpClient 5 — implementação síncrona/bloqueante que não suporta HTTP/2.

**Estado atual (Fase 1 concluída):** o componente foi migrado para `camel-quarkus-vertx-http` com `WebClientOptions`. O cliente Vert.x suporta HTTP/2 nativamente via ALPN — basta adicionar `setUseAlpn(true)` na Fase 2.

---

## Resumo das Causas

| Leg | Componente afetado | Causa raiz | Estado |
|---|---|---|---|
| Entrada (cliente → bridge) | `application.properties` | `quarkus.http.http2` ausente | Pendente (Fase 2) |
| Saída (bridge → backend) | `HttpComponentCustomizer` | Apache HC5 bloqueante, HTTP/1.1 only | ✅ Resolvido na Fase 1 |

---

## Compatibilidade com HTTP/1.1

### Leg de entrada — `quarkus.http.http2=true`

Adicionar essa propriedade faz o Vert.x anunciar `["h2", "http/1.1"]` via ALPN. A negociação é automática:

| Cliente | Resultado |
|---|---|
| Suporta h2, envia ALPN com `h2` | Negocia HTTP/2 |
| Suporta apenas http/1.1 | Negocia HTTP/1.1 |
| Não envia ALPN (clientes antigos) | Tratado como HTTP/1.1 |

**Resultado: nenhum cliente HTTP/1.1 existente é quebrado.**

### Leg de saída — `camel-quarkus-vertx-http` com ALPN

| Modo | Backend HTTP/1.1-only |
|---|---|
| ALPN habilitado (`setUseAlpn(true)`) | Negocia http/1.1 automaticamente — **compatível** |
| HTTP/2 forçado (sem ALPN) | Conexão falha — **incompatível** |

**Requisito:** configurar `setUseAlpn(true)` — nunca `setProtocolVersion(HTTP_2)` isolado.
