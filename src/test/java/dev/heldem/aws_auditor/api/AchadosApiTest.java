package dev.heldem.aws_auditor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.persistencia.AchadoItem;
import dev.heldem.aws_auditor.persistencia.RepositorioDeAchados;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

/** A API de achados, ponta a ponta, contra o DynamoDB Local (Testcontainers) e com um relógio que o teste controla. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AchadosApiTest.Configuracao.class)
class AchadosApiTest {

    static final String CHAVE = "chave-de-api-so-para-teste";

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb").withExposedPorts(8000);

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("auditor.dynamodb.endpoint", () -> "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000));
        registro.add("auditor.dynamodb.tabela", () -> "achados-api-teste");
        registro.add("auditor.api.chave", () -> CHAVE);
    }

    /** O relógio da aplicação nos testes: avança quando o teste manda. */
    static class RelogioMutavel extends Clock {
        final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-11-04T12:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora.get();
        }
    }

    @TestConfiguration
    static class Configuracao {
        @Bean
        @Primary
        RelogioMutavel relogioMutavel() {
            return new RelogioMutavel();
        }

        /** Credenciais de mentira: o DynamoDB Local só precisa de ALGUMAS para assinar a chamada. */
        @Bean
        @Primary
        AwsCredentialsProvider credenciaisDeTeste() {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create("testeaccesskey", "testesecretkey"));
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    RepositorioDeAchados repositorio;
    @Autowired
    DynamoDbTable<AchadoItem> tabela;
    @Autowired
    RelogioMutavel relogio;

    final Instant inicio = Instant.parse("2026-11-04T12:00:00Z");

    @BeforeEach
    void limparTabela() {
        repositorio.criarTabelaSeNaoExistir();
        tabela.scan().items().forEach(tabela::deleteItem);
        relogio.agora.set(inicio);
    }

    static Achado achado(String recurso, String regra, Severidade severidade) {
        return Achado.aberto(regra, severidade, "arn:aws:ec2:us-east-1:123456789012:" + recurso, "descricao de " + recurso,
                Instant.EPOCH);
    }

    String id(String recurso, String regra) {
        return new IdDoAchado("arn:aws:ec2:us-east-1:123456789012:" + recurso, regra).codificar();
    }

    void semear() {
        repositorio.registrar(List.of(achado("volume/vol-m", "VOLUME_SEM_ANEXO", Severidade.MEDIA),
                achado("security-group/sg-c", "SECURITY_GROUP_ABERTO:22", Severidade.CRITICA),
                achado("bucket/b-a", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA)), inicio);
    }

    MockMvcTester.MockMvcRequestBuilder get(String uri) {
        return mvc.get().uri(uri).header("X-Api-Key", CHAVE);
    }

    // --- chave de API ---

    @Test
    void semChaveOuComChaveErradaDa401EComChaveCertaPassa() {
        assertThat(mvc.get().uri("/achados").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/achados").header("X-Api-Key", "errada").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/achados").header("X-Api-Key", CHAVE + "x").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(get("/achados").exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void caminhoInexistenteTambemPedeAChaveEntaoNaoDaParaDescobrirRotas() {
        assertThat(mvc.get().uri("/qualquer-coisa").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(get("/qualquer-coisa").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void soASaudeEPublicaEAsMetricasExigemAChave() {
        assertThat(mvc.get().uri("/actuator/health").exchange()).hasStatus(HttpStatus.OK);
        assertThat(mvc.get().uri("/actuator/metrics").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/metrics").exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void semChaveConfiguradaOFiltroRecusaTudoFalhaFechada() throws Exception {
        var filtro = new ChaveDeApiFilter("");
        var requisicao = new org.springframework.mock.web.MockHttpServletRequest("GET", "/achados");
        requisicao.addHeader("X-Api-Key", "");
        var resposta = new org.springframework.mock.web.MockHttpServletResponse();

        filtro.doFilter(requisicao, resposta, new org.springframework.mock.web.MockFilterChain());

        assertThat(resposta.getStatus()).isEqualTo(401);
    }

    // --- listagem ---

    @Test
    void listaOsAbertosDoMaisGraveParaOMenosGraveEFiltraPorSeveridadeNoIndice() {
        semear();

        assertThat(get("/achados").exchange()).hasStatus(HttpStatus.OK).bodyJson()
                .extractingPath("$.itens[*].severidade").asList().containsExactly("CRITICA", "ALTA", "MEDIA");
        assertThat(get("/achados?severidadeMinima=ALTA").exchange()).bodyJson().extractingPath("$.itens[*].regra")
                .asList().containsExactly("SECURITY_GROUP_ABERTO:22", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS");
        assertThat(get("/achados?status=RESOLVIDO").exchange()).bodyJson().extractingPath("$.itens").asList().isEmpty();
    }

    @Test
    void paginaPorCursorSemRepetirNemPerderAchados() {
        semear();

        var primeira = get("/achados?limite=2").exchange();
        assertThat(primeira).bodyJson().extractingPath("$.itens[*].severidade").asList().containsExactly("CRITICA", "ALTA");
        String cursor = cursorDe(primeira);
        assertThat(cursor).isNotBlank();

        var segunda = get("/achados?limite=2&cursor=" + cursor).exchange();
        assertThat(segunda).bodyJson().extractingPath("$.itens[*].severidade").asList().containsExactly("MEDIA");
    }

    private String cursorDe(org.springframework.test.web.servlet.assertj.MvcTestResult resultado) {
        try {
            return new tools.jackson.databind.json.JsonMapper().readTree(resultado.getResponse().getContentAsString())
                    .get("proximoCursor").asString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void limiteECursorInvalidosDao400() {
        semear();
        assertThat(get("/achados?limite=0").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get("/achados?limite=101").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get("/achados?cursor=lixo").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get("/achados?status=INEXISTENTE").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void buscaPorIdEDa404ParaIdQueNaoExisteOuNaoDecodifica() {
        semear();

        assertThat(get("/achados/" + id("volume/vol-m", "VOLUME_SEM_ANEXO")).exchange()).hasStatus(HttpStatus.OK).bodyJson()
                .extractingPath("$.severidade").isEqualTo("MEDIA");
        assertThat(get("/achados/" + id("volume/nao-existe", "VOLUME_SEM_ANEXO")).exchange()).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get("/achados/lixo-que-nao-decodifica").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    // --- ignorar ---

    String corpo(String justificativa, Instant expira) {
        return "{\"justificativa\":%s,\"expiraEm\":\"%s\"}".formatted(justificativa == null ? "null" : "\"" + justificativa + "\"", expira);
    }

    @Test
    void ignorarExigeJustificativaEValidadeNoFuturoDeNoMaximoUmAno() {
        semear();
        String uri = "/achados/" + id("volume/vol-m", "VOLUME_SEM_ANEXO") + "/ignorar";
        var json = MediaType.APPLICATION_JSON;

        assertThat(mvc.post().uri(uri).header("X-Api-Key", CHAVE).contentType(json)
                .content(corpo(null, inicio.plus(Duration.ofDays(7)))).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri(uri).header("X-Api-Key", CHAVE).contentType(json)
                .content(corpo("   ", inicio.plus(Duration.ofDays(7)))).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri(uri).header("X-Api-Key", CHAVE).contentType(json)
                .content(corpo("ok", inicio.minusSeconds(1))).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.post().uri(uri).header("X-Api-Key", CHAVE).contentType(json)
                .content(corpo("ok", inicio.plus(Duration.ofDays(366)))).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get("/achados/" + id("volume/vol-m", "VOLUME_SEM_ANEXO")).exchange()).bodyJson()
                .extractingPath("$.status").isEqualTo("ABERTO"); // nada mudou
    }

    @Test
    void ignorarEscondeDosAbertosEPassadaADataOAchadoVoltaAAparecer() {
        semear();
        String id = id("volume/vol-m", "VOLUME_SEM_ANEXO");
        Instant expira = inicio.plus(Duration.ofDays(7));

        var ignorado = mvc.post().uri("/achados/" + id + "/ignorar").header("X-Api-Key", CHAVE)
                .contentType(MediaType.APPLICATION_JSON).content(corpo("aceito: volume de testes", expira)).exchange();

        assertThat(ignorado).hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("IGNORADO");
        assertThat(ignorado).bodyJson().extractingPath("$.justificativa").isEqualTo("aceito: volume de testes");
        assertThat(get("/achados").exchange()).bodyJson().extractingPath("$.itens[*].regra").asList()
                .doesNotContain("VOLUME_SEM_ANEXO"); // sumiu dos abertos
        assertThat(get("/achados?status=IGNORADO").exchange()).bodyJson().extractingPath("$.itens[*].regra").asList()
                .containsExactly("VOLUME_SEM_ANEXO");

        relogio.agora.set(expira.plusSeconds(1)); // passou a data
        assertThat(get("/achados").exchange()).bodyJson().extractingPath("$.itens[*].regra").asList()
                .contains("VOLUME_SEM_ANEXO"); // voltou a aparecer
        assertThat(get("/achados/" + id).exchange()).bodyJson().extractingPath("$.status").isEqualTo("ABERTO");
        assertThat(get("/achados?status=IGNORADO").exchange()).bodyJson().extractingPath("$.itens").asList().isEmpty();
    }

    @Test
    void ignoradoContinuaIgnoradoAteADataMesmoComANovaExecucaoVendoOAchado() {
        semear();
        String id = id("volume/vol-m", "VOLUME_SEM_ANEXO");
        mvc.post().uri("/achados/" + id + "/ignorar").header("X-Api-Key", CHAVE).contentType(MediaType.APPLICATION_JSON)
                .content(corpo("aceito", inicio.plus(Duration.ofDays(7)))).exchange();

        relogio.agora.set(inicio.plus(Duration.ofDays(1)));
        repositorio.registrar(List.of(achado("volume/vol-m", "VOLUME_SEM_ANEXO", Severidade.MEDIA)), relogio.instant());

        assertThat(get("/achados/" + id).exchange()).bodyJson().extractingPath("$.status").isEqualTo("IGNORADO");
    }

    @Test
    void ignorarAchadoResolvidoDa409EInexistenteDa404() {
        semear();
        repositorio.registrar(List.of(), inicio.plusSeconds(60)); // tudo sumiu: resolvido
        String id = id("volume/vol-m", "VOLUME_SEM_ANEXO");
        relogio.agora.set(inicio.plusSeconds(120));

        assertThat(mvc.post().uri("/achados/" + id + "/ignorar").header("X-Api-Key", CHAVE)
                .contentType(MediaType.APPLICATION_JSON).content(corpo("x", inicio.plus(Duration.ofDays(2)))).exchange())
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(mvc.post().uri("/achados/" + id("volume/nao-existe", "R") + "/ignorar").header("X-Api-Key", CHAVE)
                .contentType(MediaType.APPLICATION_JSON).content(corpo("x", inicio.plus(Duration.ofDays(2)))).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }
}
