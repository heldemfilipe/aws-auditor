package dev.heldem.aws_auditor.persistencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.StatusAchado;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Persistência e comparação entre execuções contra o DynamoDB Local de verdade (Testcontainers, sem conta AWS nem token).
 * Cada teste usa uma tabela própria (nome com UUID), então um não enxerga os dados do outro.
 */
@Testcontainers
class RepositorioDeAchadosTest {

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb").withExposedPorts(8000);

    static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
    static final Instant T2 = Instant.parse("2026-11-02T10:00:00Z");
    static final Instant T3 = Instant.parse("2026-11-03T10:00:00Z");

    DynamoDbClient dynamo;
    String nomeDaTabela;
    RepositorioDeAchados repositorio;

    @BeforeEach
    void criarTabela() {
        dynamo = DynamoDbClient.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("teste", "teste"))).build();
        String nome = nomeDaTabela = "achados-" + UUID.randomUUID();
        var tabela = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build().table(nome,
                TableSchema.fromBean(AchadoItem.class));
        repositorio = new RepositorioDeAchados(tabela, dynamo, nome);
        repositorio.criarTabelaSeNaoExistir();
    }

    static Achado achado(String recurso, String regra, Severidade severidade) {
        return Achado.aberto(regra, severidade, "arn:aws:ec2:us-east-1:123456789012:volume/" + recurso,
                "descricao de " + recurso, T1);
    }

    final Achado volumeA = achado("vol-a", "VOLUME_SEM_ANEXO", Severidade.MEDIA);
    final Achado volumeB = achado("vol-b", "VOLUME_SEM_ANEXO", Severidade.MEDIA);
    final Achado sgSsh = achado("sg-1", "SECURITY_GROUP_ABERTO:22", Severidade.CRITICA);
    final Achado sgRdp = achado("sg-1", "SECURITY_GROUP_ABERTO:3389", Severidade.CRITICA);

    AchadoItem item(Achado a) {
        return repositorio.todos().stream().filter(i -> i.getArn().equals(a.arn()) && i.getRegra().equals(a.regra()))
                .findFirst().orElseThrow();
    }

    // --- Experimento: repetição ---

    @Test
    void rodarDuasVezesSemMudarNadaNaoGeraNovosNemDuplicados() {
        var primeira = repositorio.registrar(List.of(volumeA, volumeB, sgSsh), T1);
        var segunda = repositorio.registrar(List.of(volumeA, volumeB, sgSsh), T2);

        assertThat(primeira).isEqualTo(new ResultadoDoDiff(3, 0, 0, 0, 0));
        assertThat(segunda).isEqualTo(new ResultadoDoDiff(0, 3, 0, 0, 0)); // zero novos
        assertThat(repositorio.todos()).hasSize(3); // nenhum duplicado
        var i = item(volumeA);
        assertThat(i.getPrimeiraVezVisto()).isEqualTo(T1.toEpochMilli()); // a primeira vez não muda
        assertThat(i.getUltimaVezVisto()).isEqualTo(T2.toEpochMilli()); // o "visto por último" avança
    }

    // --- Experimento: correção ---

    @Test
    void achadoQueSumiViraResolvidoComDataEContinuaNaTabela() {
        repositorio.registrar(List.of(volumeA, volumeB), T1);

        var resultado = repositorio.registrar(List.of(volumeA), T2); // o volume B foi removido

        assertThat(resultado).isEqualTo(new ResultadoDoDiff(0, 1, 1, 0, 0));
        var resolvido = item(volumeB);
        assertThat(resolvido.getStatus()).isEqualTo(StatusAchado.RESOLVIDO);
        assertThat(resolvido.getResolvidoEm()).isEqualTo(T2.toEpochMilli());
        assertThat(repositorio.todos()).hasSize(2); // não foi apagado
        assertThat(item(volumeA).getStatus()).isEqualTo(StatusAchado.ABERTO);

        // rodar de novo sem o B não resolve outra vez (e não muda a data de resolução)
        assertThat(repositorio.registrar(List.of(volumeA), T3).resolvidos()).isZero();
        assertThat(item(volumeB).getResolvidoEm()).isEqualTo(T2.toEpochMilli());
    }

    @Test
    void achadoResolvidoQueVoltaEReabertoMantendoAPrimeiraVezVisto() {
        repositorio.registrar(List.of(volumeA), T1);
        repositorio.registrar(List.of(), T2); // resolveu

        var resultado = repositorio.registrar(List.of(volumeA), T3); // voltou

        assertThat(resultado).isEqualTo(new ResultadoDoDiff(0, 0, 0, 1, 0));
        var i = item(volumeA);
        assertThat(i.getStatus()).isEqualTo(StatusAchado.ABERTO);
        assertThat(i.getReabertoEm()).isEqualTo(T3.toEpochMilli());
        assertThat(i.getResolvidoEm()).isNull();
        assertThat(i.getPrimeiraVezVisto()).isEqualTo(T1.toEpochMilli());
    }

    @Test
    void ssheRdpDoMesmoSecurityGroupSaoAchadosDistintosQueSeResolvemSeparados() {
        repositorio.registrar(List.of(sgSsh, sgRdp), T1);
        assertThat(repositorio.todos()).hasSize(2);

        repositorio.registrar(List.of(sgRdp), T2); // fechou só o SSH

        assertThat(item(sgSsh).getStatus()).isEqualTo(StatusAchado.RESOLVIDO);
        assertThat(item(sgRdp).getStatus()).isEqualTo(StatusAchado.ABERTO);
    }

    @Test
    void achadoIgnoradoPorUmHumanoContinuaIgnoradoMesmoApareceNovamente() {
        repositorio.registrar(List.of(volumeA), T1);
        dynamo.updateItem(UpdateItemRequest.builder().tableName(nomeDaTabela).key(chaveDe(volumeA))
                .updateExpression("SET #s = :ignorado").expressionAttributeNames(java.util.Map.of("#s", "status"))
                .expressionAttributeValues(java.util.Map.of(":ignorado", AttributeValue.fromS("IGNORADO"))).build());

        var resultado = repositorio.registrar(List.of(volumeA), T2);

        assertThat(resultado).isEqualTo(new ResultadoDoDiff(0, 0, 0, 0, 1));
        assertThat(item(volumeA).getStatus()).isEqualTo(StatusAchado.IGNORADO);
        // e se some, também não vira RESOLVIDO: a decisão humana não é desfeita pela varredura
        assertThat(repositorio.registrar(List.of(), T3).resolvidos()).isZero();
        assertThat(item(volumeA).getStatus()).isEqualTo(StatusAchado.IGNORADO);
    }

    @Test
    void execucaoAtrasadaNaoResolveOQueUmaMaisNovaAcabouDeVer() {
        repositorio.registrar(List.of(volumeA), T1);
        repositorio.registrar(List.of(volumeA), T3); // execução mais nova viu o achado em T3

        // uma execução atrasada, de T2, que não o viu, tenta resolvê-lo: a condição (visto antes de T2) não vale
        var atrasada = repositorio.registrar(List.of(), T2);

        assertThat(atrasada.resolvidos()).isZero();
        assertThat(item(volumeA).getStatus()).isEqualTo(StatusAchado.ABERTO);
    }

    // --- Índice secundário ---

    @Test
    void indiceDevolveAbertosGravesESoOs() {
        repositorio.registrar(List.of(volumeA, sgSsh, achado("bucket-x", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA)),
                T1);
        repositorio.registrar(List.of(volumeA, achado("bucket-x", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA)), T2);
        // o SSH foi resolvido; o volume (MEDIA) é aberto mas não grave

        var graves = repositorio.abertosGraves();

        assertThat(graves).extracting(Achado::regra).containsExactly("BUCKET_SEM_BLOCK_PUBLIC_ACCESS");
        assertThat(repositorio.porStatus(StatusAchado.RESOLVIDO)).extracting(Achado::regra)
                .containsExactly("SECURITY_GROUP_ABERTO:22");
        assertThat(repositorio.porStatus(StatusAchado.ABERTO)).extracting(Achado::severidade)
                .containsExactly(Severidade.ALTA, Severidade.MEDIA); // do mais grave para o menos grave
    }

    // --- Experimento: concorrência ---

    @Test
    void execucoesSimultaneasNaoDuplicamGracasAEscritaCondicional() throws Exception {
        int execucoes = 8;
        var achados = new ArrayList<Achado>();
        for (int i = 0; i < 25; i++) {
            achados.add(achado("vol-" + i, "VOLUME_SEM_ANEXO", Severidade.MEDIA));
        }
        var largada = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(execucoes);
        var resultados = new ArrayList<Future<ResultadoDoDiff>>();
        for (int i = 0; i < execucoes; i++) {
            resultados.add(executor.submit(() -> {
                largada.await();
                return repositorio.registrar(achados, T1);
            }));
        }
        largada.countDown();
        int novosNoTotal = 0;
        int persistentesNoTotal = 0;
        for (var futuro : resultados) {
            var r = futuro.get();
            novosNoTotal += r.novos();
            persistentesNoTotal += r.persistentes();
        }
        executor.shutdown();

        assertThat(repositorio.todos()).hasSize(25); // nenhuma duplicata
        assertThat(novosNoTotal).as("cada achado é 'novo' para UMA só das execuções").isEqualTo(25);
        assertThat(persistentesNoTotal).isEqualTo(25 * (execucoes - 1));
        System.out.println("CONCORRENCIA: " + execucoes + " execuções simultâneas, 25 achados: novos=" + novosNoTotal
                + ", persistentes=" + persistentesNoTotal + ", itens na tabela=" + repositorio.todos().size());
    }

    @Test
    void resolucaoSimultaneaResolveUmaSoVez() throws Exception {
        repositorio.registrar(List.of(volumeA, volumeB), T1);
        var largada = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(6);
        var resultados = new ArrayList<Future<ResultadoDoDiff>>();
        for (int i = 0; i < 6; i++) {
            resultados.add(executor.submit(() -> {
                largada.await();
                return repositorio.registrar(List.of(volumeA), T2); // o volume B sumiu para as seis execuções
            }));
        }
        largada.countDown();
        int resolvidos = 0;
        for (var futuro : resultados) {
            resolvidos += futuro.get().resolvidos();
        }
        executor.shutdown();

        assertThat(resolvidos).isEqualTo(1);
        assertThat(item(volumeB).getResolvidoEm()).isEqualTo(T2.toEpochMilli());
    }

    private java.util.Map<String, AttributeValue> chaveDe(Achado a) {
        return java.util.Map.of("arn", AttributeValue.fromS(a.arn()), "regra", AttributeValue.fromS(a.regra()));
    }
}
