# Atributos Configuráveis — bridge.*

Todos os atributos são gerenciados pelo `@ConfigMapping(prefix = "bridge")` em `BridgeConfig`. O valor de cada atributo pode ser fornecido via arquivo de propriedades ou variável de ambiente (SmallRye Config converte automaticamente: `.` e `-` → `_`, uppercase).

---

## Roteamento

| Atributo | Variável de Ambiente | Tipo | Padrão | Descrição |
|---|---|---|---|---|
| `bridge.target.url` | `BRIDGE_TARGET_URL` | `String` | `https://localhost:9443` | URL base do backend. Sem barra final. Aceita `http://` e `https://`. |

---

## Certificado Cliente — Subject DN

| Atributo | Variável de Ambiente | Tipo | Padrão | Descrição |
|---|---|---|---|---|
| `bridge.dn.header-name` | `BRIDGE_DN_HEADER_NAME` | `String` | `x-cert-client-subject-dn` | Nome do header HTTP pelo qual o Subject DN do certificado cliente é repassado ao backend. Sempre injetado quando há sessão mTLS. |

---

## HTTP Client (Bridge → Backend)

| Atributo | Variável de Ambiente | Tipo | Padrão | Descrição |
|---|---|---|---|---|
| `bridge.http-client.max-pool-size` | `BRIDGE_HTTP_CLIENT_MAX_POOL_SIZE` | `int` | `25` | Número máximo de conexões simultâneas ao backend por `host:porta`. |
| `bridge.http-client.verify-host` | `BRIDGE_HTTP_CLIENT_VERIFY_HOST` | `boolean` | `false` | Valida que o hostname do certificado do backend corresponde ao host da URL. Desabilitar para backends com certificado auto-assinado. |

---

## Certificado Cliente — Campos Opcionais

Cada atributo define o nome do header pelo qual o respectivo campo do certificado é repassado ao backend. **Se o atributo não estiver configurado (ou tiver valor vazio), o campo não é extraído nem injetado.** Nenhum desses atributos possui valor padrão — estão inativos por omissão.

| Atributo | Variável de Ambiente | Campo do Certificado | Formato do Valor |
|---|---|---|---|
| `bridge.cert.issuer-dn.header-name` | `BRIDGE_CERT_ISSUER_DN_HEADER_NAME` | DN da CA emissora | DN string (ex.: `C=BR,O=Exemplo,CN=CA`) |
| `bridge.cert.serial.header-name` | `BRIDGE_CERT_SERIAL_HEADER_NAME` | Número de série | Hexadecimal lowercase |
| `bridge.cert.not-before.header-name` | `BRIDGE_CERT_NOT_BEFORE_HEADER_NAME` | Início da validade | ISO-8601 UTC (ex.: `2026-05-29T16:52:46Z`) |
| `bridge.cert.not-after.header-name` | `BRIDGE_CERT_NOT_AFTER_HEADER_NAME` | Fim da validade | ISO-8601 UTC |
| `bridge.cert.fingerprint.header-name` | `BRIDGE_CERT_FINGERPRINT_HEADER_NAME` | Fingerprint SHA-256 | Hexadecimal lowercase (64 caracteres) |
| `bridge.cert.san.header-name` | `BRIDGE_CERT_SAN_HEADER_NAME` | Subject Alternative Names | CSV `tipo:valor` (ex.: `DNS:svc.local,IP:10.0.0.1`). Ausente se o certificado não tiver SANs. |
| `bridge.cert.pem.header-name` | `BRIDGE_CERT_PEM_HEADER_NAME` | Certificado completo (DER) | Base64 sem quebras de linha. Reconstrução do PEM: prefixar/sufixar com `-----BEGIN/END CERTIFICATE-----`. |

### Exemplo de ativação via variável de ambiente

```bash
BRIDGE_TARGET_URL=https://backend:8443 \
BRIDGE_CERT_ISSUER_DN_HEADER_NAME=x-cert-client-issuer-dn \
BRIDGE_CERT_SERIAL_HEADER_NAME=x-cert-client-serial \
BRIDGE_CERT_NOT_BEFORE_HEADER_NAME=x-cert-client-not-before \
BRIDGE_CERT_NOT_AFTER_HEADER_NAME=x-cert-client-not-after \
BRIDGE_CERT_FINGERPRINT_HEADER_NAME=x-cert-client-fingerprint \
BRIDGE_CERT_SAN_HEADER_NAME=x-cert-client-san \
BRIDGE_CERT_PEM_HEADER_NAME=x-cert-client-cert \
bash infra/start-podman.sh
```
