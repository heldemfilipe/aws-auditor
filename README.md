# aws-auditor

> **Status:** projeto de estudo, em construção. Pronto: o projeto gerado, o BOM do SDK da AWS, o CI (Etapa 0) e o motor de
> regras testado, sem nenhuma linha do SDK (Etapa 1).
> O que não existe está marcado como "a fazer".

## Problema

Uma conta AWS acumula desperdício e risco sem ninguém notar: volumes EBS soltos (pagando sem uso), buckets S3 sem bloqueio de
acesso público, chaves IAM paradas e security groups abertos para a internet. Este auditor varre a conta, aplica regras,
guarda os achados e diz o que é novo, o que persiste e o que foi resolvido desde a última execução.

## Arquitetura

```
dominio/   records e enums: Recurso (sealed: VolumeEbs, BucketS3, ChaveIam, SecurityGroup), Achado, Severidade, StatusAchado
regras/    interface Regra e as quatro regras (puras: recebem um Recurso, devolvem achados)
motor/     MotorDeRegras: aplica TODAS as regras (injetadas pelo Spring) a todos os recursos
```

O pacote `dominio`, o de `regras` e o `motor` **não importam `software.amazon.awssdk`**: um teste lê os fontes e falha se
algum importar (`MotorDeRegrasTest`). A conversão do SDK para o domínio será um adapter à parte (Etapa 2), a persistência no
DynamoDB (Etapa 3) e o job que roda tudo e sai com código 0 ou 1 (Etapa 6): a fazer.

## Como rodar

```bash
./mvnw verify
```

## Regras

| Regra (`id`) | Dispara quando | Severidade |
|---|---|---|
| `VOLUME_SEM_ANEXO` | volume EBS sem anexo há **mais** de N dias (padrão 30, `auditor.regras.volume-sem-anexo-dias`) | MEDIA |
| `BUCKET_SEM_BLOCK_PUBLIC_ACCESS` | alguma das quatro proteções do block public access está desligada (ou a configuração não existe) | ALTA |
| `CHAVE_IAM_PARADA` | chave de acesso **ativa** sem uso há mais de N dias (padrão 90, `auditor.regras.chave-iam-dias`); nunca usada conta da criação | MEDIA |
| `SECURITY_GROUP_ABERTO` | entrada TCP (ou todos os protocolos) de `0.0.0.0/0` ou `::/0` cobrindo a porta 22 (SSH) ou 3389 (RDP); um achado por porta | CRITICA |

Casos de borda testados (`RegrasTest`, dados sintéticos): exatamente N dias **não** é achado e N dias mais 1 segundo é;
bloqueio faltando só uma das quatro proteções já é achado; intervalo de portas que termina em 22 ou começa em 22 conta, o
que termina em 21 ou começa em 23 não; UDP na porta 22 não conta; origem `10.0.0.0/8` não conta.

**Limite do que o domínio sabe:** `VolumeEbs.semAnexoDesde` é o instante em que o volume ficou sem anexo, mas a API
`DescribeVolumes` da AWS **não informa** quando um volume foi desanexado. O adapter da Etapa 2 vai ter que escolher uma
aproximação (a criação do volume, ou o primeiro momento em que o auditor o viu solto) e dizer qual: a fazer.

## Decisões

- **Java 25 e Spring Boot 4.1.2**, a mesma base dos outros projetos.
- **AWS SDK for Java v2**, com o BOM fixando a versão (2.57.8) de todos os módulos.
- **Nada da sua conta no repositório:** `.env`, `*.pem`, `reports/` e `local/` estão no `.gitignore`, e os exemplos em
  `docs/exemplos/` são só sintéticos.
- **`Recurso` é `sealed`:** os quatro tipos são fechados, e o `switch` que dá o tipo (`Recurso.tipo()`) é exaustivo: acrescentar
  um tipo novo sem tratá-lo não compila.
- **Regras injetadas pelo Spring:** o motor recebe `List<Regra>`; uma regra nova é um `@Component`, sem tocar no motor
  (`MotorDeRegrasTest` prova com uma regra de teste).
- **Relógio injetado (`Clock`):** as regras não chamam `Instant.now()`, então os testes fixam o tempo e testam o "exatamente
  N dias".
- **Sem LocalStack:** ele exige token de autenticação. Os testes usarão o DynamoDB Local (Testcontainers) e a lógica de regras
  isolada do SDK.

## Experimentos

A fazer (Etapas 2 a 4).

## Custo

**Zero até aqui.** Nada é executado contra uma conta AWS: o projeto roda e testa só no computador.

## Roteiro

- [x] Etapa 0: repositório, projeto gerado, BOM do SDK e CI
- [x] Etapa 1: domínio e regras, sem AWS (quatro regras, 26 testes)
- [ ] Etapa 2: integração com o SDK v2
- [ ] Etapa 3: DynamoDB e comparação entre execuções
- [ ] Etapa 4: paralelismo e métricas
- [ ] Etapa 5: API e alertas
- [ ] Etapa 6: container, agendamento e deploy

## Licença

MIT, veja [LICENSE](LICENSE).
