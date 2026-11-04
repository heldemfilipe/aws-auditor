# aws-auditor

> **Status:** projeto de estudo, em construção. Pronto: o projeto gerado, o BOM do SDK da AWS, o CI (Etapa 0), o motor de
> regras testado, sem nenhuma linha do SDK (Etapa 1), a integração com o SDK v2 (Etapa 2) e a persistência dos achados no
> DynamoDB com a comparação entre execuções (Etapa 3) a varredura de várias regiões em paralelo, com métricas (Etapa 4) e a API de achados com o aviso no Telegram (Etapa 5).
> **A varredura nunca rodou contra uma conta AWS real**: tudo foi testado com respostas sintéticas, um servidor HTTP local e o DynamoDB Local.
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
algum importar (`MotorDeRegrasTest`).

```
aws/       a ÚNICA parte que importa o SDK: ClientesAws, um Coletor por serviço (adapter SDK -> domínio) e VarreduraDaConta
```

`VarreduraDaConta` descobre a conta (STS `GetCallerIdentity`, que não exige permissão IAM), coleta os serviços globais (S3 e
IAM) uma vez e os regionais (EC2) em cada região, e entrega tudo ao motor. A persistência dos achados (`persistencia/`,
DynamoDB, Etapa 3) compara cada execução com a anterior. O job que sai com código 0 ou 1 (Etapa 6): a fazer.

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
`DescribeVolumes` da AWS **não informa** quando um volume foi desanexado. O adapter usa a **criação do volume** (`createTime`),
que é um limite SUPERIOR do tempo parado: um volume antigo desanexado ontem aparece como "sem anexo há meses" (falso
positivo possível). A Etapa 3 pode refinar com o "primeira vez visto" do achado.

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

### SDK v2: retry e throttling (Etapa 2)

Um servidor HTTP local faz o papel do EC2 e devolve `RequestLimitExceeded` (HTTP 503, o throttling do EC2) nas primeiras
chamadas. O cliente é o **real** do SDK v2, apontado para ele (`auditor.aws.endpoint`), então o retry exercitado é o do SDK,
não um stub nosso (`RetryTest`). As credenciais do teste são valores de mentira.

| Experimento | Como montei | Observado |
|---|---|---|
| Throttling | o servidor falha as 2 primeiras chamadas; até 3 tentativas | o SDK repetiu e a varredura terminou: **3 chamadas em 697 ms**, com espera crescente entre elas |
| Sem retry | mesmo servidor, 1 tentativa (`auditor.aws.retry-max-tentativas=1`) | a varredura **falhou** com `Ec2Exception` ("Request limit exceeded"): **1 chamada** e o erro aparece |
| Limite do retry | o servidor falha 10 vezes; até 3 tentativas | **desistiu depois de 3 chamadas** e falhou: retry não é infinito |

O retry é a estratégia padrão do SDK (espera exponencial com jitter, só para erros que valem repetir). O número de
tentativas é configurável. Medi uma vez: o tempo (697 ms) varia por causa do jitter.

### API de achados e aviso no Telegram (Etapa 5)

**Autenticação.** Chave no cabeçalho `X-Api-Key`, lida da variável de ambiente `AUDITOR_API_KEY` e comparada em **tempo
constante** (`MessageDigest.isEqual` sobre os SHA-256 das duas chaves; o hash primeiro, para o tamanho da chave verdadeira não
vazar). Decisões:

- **Falha fechada:** sem a variável definida, a API e as métricas recusam tudo (nenhuma chave "vazia" é aceita).
- **401 antes de 404:** um caminho que não existe também pede a chave; sem ela não dá para descobrir rotas.
- **Só `/actuator/health` é público**, para o orquestrador. `/actuator/metrics` (as métricas da Etapa 4) exige a chave.
- **Por que chave e não JWT:** este projeto não tem provedor de identidade; é uma ferramenta de um operador, e uma chave
  rotacionável por variável de ambiente basta. Com um Keycloak (como no room-booking-api), seria JWT.

**Endpoints** (todos com `X-Api-Key`):

| Método e caminho | O que faz |
|---|---|
| `GET /achados` | achados de um `status` (padrão `ABERTO`), do mais grave para o menos grave, paginados por `cursor` e `limite` (1 a 100); `severidadeMinima=ALTA` devolve ALTA e CRITICA e filtra **no próprio índice** |
| `GET /achados/{id}` | um achado; o `id` é opaco (o par ARN + regra em base64 seguro para URL: o ARN tem `/` e `:`) |
| `POST /achados/{id}/ignorar` | `{"justificativa": "...", "expiraEm": "2026-12-01T00:00:00Z"}` |

**Ignorar** exige justificativa (até 500 caracteres) e uma validade no futuro e de **no máximo 365 dias**: ignorar sem validade
vira esquecer para sempre. **Passada a data, o achado volta a ABERTO e a aparecer** (a reabertura é feita ao listar, ao buscar
e a cada execução, de forma condicional: só se ainda está IGNORADO e vencido). Enquanto está ignorado, uma nova execução que
o veja não muda o status. Ignorar um achado RESOLVIDO dá 409; um que não existe, 404. Entrada inválida dá 400 (limite fora da
faixa, cursor que não decodifica, status inexistente, justificativa em branco, validade no passado ou longe demais).

**Aviso no Telegram** (`NotificadorTelegram`): só achados **novos** (os que esta execução criou) de severidade **ALTA ou
mais**, em **uma única mensagem por execução**, e não uma por achado (até 10 linhas, depois "... e mais N"). Sem achado novo
grave, não manda nada. O texto é puro (sem `parse_mode`): descrições vêm da conta e não podem injetar formatação. O token e o
chat vêm só de `TELEGRAM_BOT_TOKEN` e `TELEGRAM_CHAT_ID`; sem eles o aviso fica desligado. Falha do Telegram **não derruba a
auditoria**: vira um log de aviso e o contador `auditor.notificacao.erros`.

Testado com **WireMock** (nenhum bot de verdade; o token é um valor de mentira): dois achados novos graves geram **uma**
requisição com os dois; um achado de severidade média não entra; sem novo grave, **zero** requisições; Telegram respondendo
500 não propaga exceção.

**Dois achados que só o teste mostrou:**

1. **O `:` do token era codificado como `%3A`.** O token de um bot vai no caminho da URL (`/bot123456:ABC.../sendMessage`) e o
   `RestClient`, ao expandir um template de URI, codificou o dois-pontos. Não sei se o Telegram aceitaria o `%3A`, então não
   arrisquei: o URI é montado direto, com o `:` literal, e o formato do token é validado antes (`número:letras`), para só haver
   caracteres seguros. Um token fora do formato desliga o aviso e é registrado **sem mostrar o valor**.
2. **O token nunca pode aparecer em log.** Ele está na URL, e a mensagem de erro do cliente HTTP a incluiria. Só se loga o
   tipo da exceção e o status HTTP (teste: com o Telegram em 500, o log traz "falhou (HTTP 500)" e não contém o token).

**Não executado:** o aviso chegando de verdade em um chat do Telegram (precisa de um bot e de um token, que não existem aqui).

### Varredura paralela com threads virtuais (Etapa 4)

Cada par (coletor, região) é uma tarefa independente: `VarreduraDaConta` as roda em sequência ou, com
`auditor.varredura.paralela=true` (o padrão), em **threads virtuais** (`Executors.newVirtualThreadPerTaskExecutor()`), com um
**teto de chamadas simultâneas** (`auditor.varredura.paralelismo-maximo`, padrão 16). O resultado é o mesmo (a ordem dos
recursos segue a das tarefas, não a de quem terminou primeiro).

**Como medi.** Sem conta AWS, um servidor HTTP local faz o papel do STS e do EC2, com **latência fixa de 150 ms por chamada de
EC2** (a rede de verdade teria uma parecida, e variável). O cliente é o real do SDK v2. **12 regiões x 2 coletores (volumes
e security groups) = 24 chamadas.** Uma rodada de aquecimento descartada e 3 medidas por configuração (`ParalelismoTest`):

| Configuração | Tempos medidos (ms) | Observação |
|---|---|---|
| Sequencial | 3696, 3699, 3697 | 24 x 150 ms = 3.600 ms, mais o custo do SDK |
| Paralela, teto 16 | 316, 313, 317 | ~12x mais rápida: 24 tarefas em 2 "ondas" de 16 |
| Paralela, teto 4 | 935, 930, 938 | 6 ondas de 4: o ganho cai com o teto |

O servidor registrou no máximo **16 chamadas simultâneas** com o teto 16: o semáforo funciona.

**O que limitou o ganho:** o teto de chamadas simultâneas. Com 24 tarefas e teto 16, são duas ondas (16 + 8), então o tempo
é ~2 x 150 ms, e não 1 x 150 ms. Os números seguem a conta quase exatamente (3.600 / 300 / 900 ms mais o custo fixo) porque
a latência do servidor falso é fixa e ele atende tudo em paralelo. **Isto NÃO mede a AWS:** numa conta real o limite seria
outro, o throttling das APIs (que o retry da Etapa 2 absorve, mas devolve tempo) e o pool de conexões HTTP do cliente, e a
latência varia. O ganho de ~12x vale para este cenário; o número real depende da conta.

**Falha em uma tarefa:** as demais terminam, cada falha vira o contador `auditor.varredura.erros{servico=...}` e a varredura
inteira FALHA (`VarreduraFalhouException`, com todas as falhas na mensagem). Um resultado parcial não pode virar "conta
limpa" nem resolver achados de recursos que simplesmente não foram lidos (teste: negar o `DescribeVolumes` em todas as regiões
dá 12 erros do serviço `ec2` e uma exceção, sem resultado).

**Métricas (Micrometer):**

| Métrica | O que é |
|---|---|
| `auditor.varredura.duracao` (rótulo `paralela`) | tempo da varredura inteira, inclusive das que falham |
| `auditor.achados` (rótulo `severidade`) | achados da última varredura, uma série por severidade (BAIXA a CRITICA, com zero) |
| `auditor.varredura.erros` (rótulo `servico`: `ec2`, `s3`, `iam`, `sts`) | falhas por serviço |

Testadas pelo `MeterRegistry` (`ParalelismoTest`). Expostas em `/actuator/metrics`, **protegidas pela chave de API** (Etapa 5).

### DynamoDB: achados e comparação entre execuções (Etapa 3)

Todos contra o **DynamoDB Local** (`amazon/dynamodb-local`, via Testcontainers; sem conta AWS nem token), em
`RepositorioDeAchadosTest` (uma tabela nova por teste).

| Experimento | Como montei | Observado |
|---|---|---|
| Repetição | registrar os mesmos 3 achados duas vezes | 1ª execução: 3 novos. 2ª: **0 novos**, 3 persistentes; **3 itens** na tabela (nenhum duplicado); a primeira vez visto não muda e o "visto por último" avança |
| Correção | registrar 2 volumes e depois só 1 (o outro "foi removido") | o achado do volume removido vira **RESOLVIDO, com a data** e **continua na tabela**; rodar de novo não resolve outra vez nem muda a data |
| Concorrência | **8 execuções simultâneas** (largada sincronizada) dos mesmos 25 achados | **25 itens** na tabela, **25 "novos" no total** (cada achado é novo para UMA só execução) e 175 persistentes (25 x 7) |
| Resolução simultânea | 6 execuções resolvendo o mesmo achado ao mesmo tempo | resolvido **uma só vez** no total |

**Tabela.** Partição = ARN do recurso, ordenação = código da regra (a identidade do achado é o par). O índice secundário
`status-severidade` (partição = status, ordenação = nível da severidade, um número de 0 a 3) responde "achados ABERTOS e
graves" com uma consulta (`status = ABERTO` e `severidadeNivel >= ALTA`), sem varrer a tabela. Cobrança por requisição.
Limite do índice: o status tem pouquíssimos valores, então a partição `ABERTO` concentra a leitura; para o volume de
achados de uma conta isso não importa, e eu **não medi** o que aconteceria com milhões de itens.

**Escritas condicionais (`ConditionExpression`).** É o que impede a duplicação e a corrida:

- **novo:** `PutItem` com `attribute_not_exists(arn)`: dos concorrentes, só um cria;
- **persistente:** atualiza o "visto por último" só se está ABERTO e a data é mais nova;
- **resolvido:** ABERTO vira RESOLVIDO só se ninguém o viu depois deste instante. Teste: uma execução atrasada (de T2) que
  não viu o achado **não** resolve o que uma execução mais nova (T3) acabou de ver;
- **reaberto:** RESOLVIDO volta a ABERTO (com `reabertoEm`, mantendo a primeira vez visto) só se ainda está RESOLVIDO.

**IGNORADO** (a decisão humana da Etapa 5) nunca é desfeito pela varredura: continua IGNORADO mesmo que o achado reapareça ou
suma. **A identidade é (ARN, regra)**: SSH e RDP abertos no mesmo security group são dois achados (`SECURITY_GROUP_ABERTO:22`
e `:3389`) que se resolvem separadamente; nasceu de um erro de modelagem que eu só percebi ao desenhar a chave da tabela.

**Tempos em milissegundos (número), não em texto ISO:** o DynamoDB compara texto letra a letra e o ISO muda de tamanho
conforme a fração de segundo, então as condições "mais novo que" ordenariam errado.

**Limites:** o diff lê a tabela inteira (`scan`) no início de cada execução, o que serve para este tamanho e não escala
para milhões de achados; a tabela é criada por `criarTabelaSeNaoExistir()` no local, e em produção seria o Terraform
(Etapa 6, não executada). Não rodei contra o DynamoDB real: o comportamento de condição e de índice é o do DynamoDB Local.

## Integração com a AWS (Etapa 2)

- **Credenciais:** só a cadeia padrão do SDK (variáveis de ambiente, perfil, role da tarefa), resolvida de forma preguiçosa:
  a aplicação sobe sem credenciais, e nunca há chave no código nem no `application.properties`. Regiões em
  `auditor.aws.regioes` (padrão `us-east-1`).
- **Paginadores do SDK** (`describeVolumesPaginator`, `listBucketsPaginator`, `listUsersPaginator`...) em vez de laço manual
  com token; o teste exercita a paginação de verdade (duas páginas de volumes).
- **Adapters** em `aws/`: um coletor por serviço converte a resposta do SDK em recurso do domínio. Pontos que o teste cobre:
  bucket sem configuração de block public access (`NoSuchPublicAccessBlockConfiguration`) vira "nenhuma proteção", ou seja,
  o achado, mas qualquer outro erro (como `AccessDenied`) **propaga**: falta de permissão não pode virar "bucket protegido"
  nem "bucket desprotegido"; permissão de entrada `-1` (todos os protocolos) vem sem portas e vira 0 a 65535; faixas IPv4 e
  IPv6 viram regras separadas; chave IAM nunca usada vem sem data de último uso. Chave de acesso não tem ARN na AWS: o
  identificador é sintético, `<arn do usuário>/access-key/<id>`.
- **Policy IAM mínima** em [`docs/iam/politica-auditor-leitura.json`](docs/iam/politica-auditor-leitura.json), escrita à mão:
  só `Describe`, `List` e `Get`, sem curinga de ação, sem `ReadOnlyAccess`. `Resource: "*"` porque essas ações não aceitam
  restrição por recurso. Um teste (`PoliticaIamTest`) confere que a policy tem **exatamente** as ações que os coletores
  declaram: uma a mais quebra o menor privilégio, uma a menos faria a varredura falhar com `AccessDenied`.

**Não executado:** a varredura de uma conta real com uma role que tenha só essa policy (o "pronto quando" do roteiro)
nunca rodou, porque não há conta AWS em uso neste projeto. A policy é, portanto, a que os coletores DECLARAM precisar,
não uma que eu tenha visto funcionar numa role: pode faltar uma ação que só uma chamada real revelaria.

## Custo

**Zero até aqui.** Nada é executado contra uma conta AWS: o projeto roda e testa só no computador.

## Roteiro

- [x] Etapa 0: repositório, projeto gerado, BOM do SDK e CI
- [x] Etapa 1: domínio e regras, sem AWS (quatro regras, 26 testes)
- [x] Etapa 2: integração com o SDK v2 (coletores, policy mínima, retry medido; varredura real **não executada**)
- [x] Etapa 3: DynamoDB e comparação entre execuções (escritas condicionais, três experimentos medidos no DynamoDB Local)
- [x] Etapa 4: paralelismo e métricas (12 regiões: 3,7 s em sequência, 0,31 s em paralelo contra um servidor com latência fixa)
- [x] Etapa 5: API e alertas (consulta paginada pelo índice, ignorar com validade, chave em tempo constante, Telegram com WireMock; aviso real **não executado**)
- [ ] Etapa 6: container, agendamento e deploy

## Licença

MIT, veja [LICENSE](LICENSE).
