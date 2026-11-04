# aws-auditor

> **Status:** projeto de estudo, em construção. Pronto: o projeto gerado, o BOM do SDK da AWS e o CI (Etapa 0).
> O que não existe está marcado como "a fazer".

## Problema

Uma conta AWS acumula desperdício e risco sem ninguém notar: volumes EBS soltos (pagando sem uso), buckets S3 sem bloqueio de
acesso público, chaves IAM paradas e security groups abertos para a internet. Este auditor varre a conta, aplica regras,
guarda os achados e diz o que é novo, o que persiste e o que foi resolvido desde a última execução.

## Arquitetura

A fazer (Etapas 1 a 6). O desenho previsto: um **motor de regras** que não conhece a AWS (só records e regras puras), um
**adapter** que converte as respostas do SDK v2 em recursos, a **persistência** dos achados no DynamoDB e um job que roda
a varredura e sai com código 0 ou 1.

## Como rodar

```bash
./mvnw verify
```

## Regras

A fazer (Etapa 1): volume EBS sem anexo há mais de N dias, bucket S3 sem block public access, chave IAM sem uso há 90 dias,
security group com `0.0.0.0/0` nas portas 22 ou 3389.

## Decisões

- **Java 25 e Spring Boot 4.1.2**, a mesma base dos outros projetos.
- **AWS SDK for Java v2**, com o BOM fixando a versão (2.57.8) de todos os módulos.
- **Nada da sua conta no repositório:** `.env`, `*.pem`, `reports/` e `local/` estão no `.gitignore`, e os exemplos em
  `docs/exemplos/` são só sintéticos.
- **Sem LocalStack:** ele exige token de autenticação. Os testes usarão o DynamoDB Local (Testcontainers) e a lógica de regras
  isolada do SDK.

## Experimentos

A fazer (Etapas 2 a 4).

## Custo

**Zero até aqui.** Nada é executado contra uma conta AWS: o projeto roda e testa só no computador.

## Roteiro

- [x] Etapa 0: repositório, projeto gerado, BOM do SDK e CI
- [ ] Etapa 1: domínio e regras, sem AWS
- [ ] Etapa 2: integração com o SDK v2
- [ ] Etapa 3: DynamoDB e comparação entre execuções
- [ ] Etapa 4: paralelismo e métricas
- [ ] Etapa 5: API e alertas
- [ ] Etapa 6: container, agendamento e deploy

## Licença

MIT, veja [LICENSE](LICENSE).
