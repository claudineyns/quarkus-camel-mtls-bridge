# quarkus-camel-mtls-bridge

Proxy reverso mTLS construído com **Quarkus 3.27.3 (Red Hat)** e **Apache Camel**. Atua como ponto de entrada TLS mútuo: autentica clientes via certificado X.509, extrai campos configuráveis do certificado e os repassa como headers HTTP para o backend de destino.

Ambos os lados do pipeline operam no event loop do Vert.x — modelo **async/non-blocking** end-to-end, com **HTTP/2 via ALPN** em ambos os legs.

**Autor:** Claudiney Nascimento &lt;contato@claudiney.info&gt;

---

## Pré-requisitos

| Ferramenta | Versão mínima |
|---|---|
| Java (JDK) | 21 |
| Maven | 3.9+ |
| Podman | 4.x+ |

---

## Como obter

```bash
git clone https://github.com/claudineyns/quarkus-camel-mtls-bridge.git
cd quarkus-camel-mtls-bridge
```

---

## Certificados

O script de execução monta o diretório `infra/certs/` no container. Os arquivos esperados são:

| Arquivo | Descrição |
|---|---|
| `infra/certs/server.crt` | Certificado TLS do servidor (bridge) |
| `infra/certs/server.key` | Chave privada do servidor |
| `infra/certs/ca-client.crt` | CA que assinou os certificados dos clientes |

Os caminhos podem ser substituídos pelas variáveis de ambiente `BRIDGE_SERVER_CERT_PEM`, `BRIDGE_SERVER_KEY_PEM` e `BRIDGE_CLIENT_TRUST_PEM`.

---

## Executar com Podman

O script `infra/start-podman.sh` compila o projeto, constrói a imagem e inicia o container. É a **única forma suportada** de subir a aplicação — não use `podman run` diretamente.

### Uso básico

```bash
BRIDGE_TARGET_URL=https://meu-backend:8443 bash infra/start-podman.sh
```

### Com campos opcionais do certificado ativados

```bash
BRIDGE_TARGET_URL=https://meu-backend:8443 \
BRIDGE_CERT_ISSUER_DN_HEADER_NAME=x-cert-client-issuer-dn \
BRIDGE_CERT_SERIAL_HEADER_NAME=x-cert-client-serial \
BRIDGE_CERT_NOT_BEFORE_HEADER_NAME=x-cert-client-not-before \
BRIDGE_CERT_NOT_AFTER_HEADER_NAME=x-cert-client-not-after \
BRIDGE_CERT_FINGERPRINT_HEADER_NAME=x-cert-client-fingerprint \
BRIDGE_CERT_SAN_HEADER_NAME=x-cert-client-san \
BRIDGE_CERT_PEM_HEADER_NAME=x-cert-client-cert \
bash infra/start-podman.sh
```

### Portas

| Porta (host) | Porta (container) | Descrição |
|---|---|---|
| `9696` | `9443` | mTLS — tráfego de aplicação |
| `9000` | `9000` | Management (health, metrics) |

### Verificar disponibilidade

```bash
curl http://localhost:9000/q/health/ready
```

### Parar

```bash
podman rm -f bridge-app
```

---

## Chamar a bridge

Toda requisição deve apresentar um certificado cliente válido (assinado pela CA configurada em `ca-client.crt`):

```bash
curl --cert infra/certs/client.crt \
     --key  infra/certs/client.key \
     --cacert infra/certs/ca.crt \
     https://localhost:9696/seu/path
```

O Subject DN do certificado é injetado automaticamente como header `x-cert-client-subject-dn` (configurável). Os demais campos do certificado são injetados somente se as respectivas variáveis de ambiente estiverem definidas.

---

## Documentação

| Documento | Descrição |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Arquitetura, componentes e comportamento do pipeline |
| [docs/configuration.md](docs/configuration.md) | Inventário completo de atributos configuráveis (`bridge.*`) |
