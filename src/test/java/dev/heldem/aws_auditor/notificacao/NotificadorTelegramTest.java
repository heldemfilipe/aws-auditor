package dev.heldem.aws_auditor.notificacao;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import dev.heldem.aws_auditor.aws.VarreduraDaConta;
import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.persistencia.AuditoriaService;
import dev.heldem.aws_auditor.persistencia.RepositorioDeAchados;
import dev.heldem.aws_auditor.persistencia.RepositorioDeAchados.Registro;
import dev.heldem.aws_auditor.persistencia.ResultadoDoDiff;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * O aviso no Telegram contra o WireMock (nenhum bot de verdade; o token é um valor de mentira). O que importa: UMA mensagem
 * por execução, só dos achados NOVOS e de severidade ALTA ou mais, e que o token nunca apareça em log.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificadorTelegramTest {

    static final String TOKEN = "123456:TOKEN-DE-MENTIRA-SO-PARA-TESTE";
    static final String CAMINHO = "/bot" + TOKEN + "/sendMessage";

    WireMockServer telegram;
    SimpleMeterRegistry metricas = new SimpleMeterRegistry();

    @BeforeEach
    void subir() {
        telegram = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        telegram.start();
        telegram.stubFor(post(urlEqualTo(CAMINHO)).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
    }

    @AfterEach
    void parar() {
        telegram.stop();
    }

    NotificadorTelegram notificador(String token) {
        return new NotificadorTelegram(new TelegramProperties("http://localhost:" + telegram.port(), token, "42"), metricas);
    }

    static Achado achado(String recurso, String regra, Severidade severidade) {
        return Achado.aberto(regra, severidade, "arn:aws:ec2:us-east-1:123456789012:" + recurso, "d", Instant.EPOCH);
    }

    @Test
    void doisNovosGravesGeramUmaUnicaMensagemComOsDois() {
        var enviado = notificador(TOKEN).notificarNovosGraves(List.of(achado("security-group/sg-1", "SECURITY_GROUP_ABERTO:22", Severidade.CRITICA),
                achado("s3/b", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA),
                achado("volume/v-1", "VOLUME_SEM_ANEXO", Severidade.MEDIA)));

        assertThat(enviado).isTrue();
        telegram.verify(1, postRequestedFor(urlEqualTo(CAMINHO))); // uma só mensagem, não uma por achado
        telegram.verify(postRequestedFor(urlEqualTo(CAMINHO)).withRequestBody(containing("2 achado(s) novo(s)"))
                .withRequestBody(containing("SECURITY_GROUP_ABERTO:22")).withRequestBody(containing("BUCKET_SEM_BLOCK_PUBLIC_ACCESS"))
                .withRequestBody(equalToJson("{\"chat_id\":\"42\"}", true, true)));
        // o achado de severidade média não entra na mensagem
        assertThat(telegram.getAllServeEvents().get(0).getRequest().getBodyAsString()).doesNotContain("VOLUME_SEM_ANEXO");
    }

    @Test
    void semNovoGraveNaoMandaNada() {
        assertThat(notificador(TOKEN).notificarNovosGraves(List.of(achado("volume/v-1", "VOLUME_SEM_ANEXO", Severidade.MEDIA))))
                .isFalse();
        assertThat(notificador(TOKEN).notificarNovosGraves(List.of())).isFalse();
        assertThat(telegram.getAllServeEvents()).isEmpty();
    }

    @Test
    void semTokenOAvisoFicaDesligadoENaoChamaOTelegram() {
        assertThat(notificador("").notificarNovosGraves(List.of(achado("s3/b", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA))))
                .isFalse();
        assertThat(telegram.getAllServeEvents()).isEmpty();
    }

    @Test
    void falhaDoTelegramNaoDerrubaAAuditoriaENuncaVazaOTokenNoLog(CapturedOutput saida) {
        telegram.stubFor(post(urlEqualTo(CAMINHO)).willReturn(aResponse().withStatus(500)));

        var enviado = notificador(TOKEN).notificarNovosGraves(List.of(achado("s3/b", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA)));

        assertThat(enviado).isFalse(); // sem exceção
        assertThat(metricas.get("auditor.notificacao.erros").counter().count()).isEqualTo(1);
        assertThat(saida.getAll()).contains("Aviso no Telegram falhou (HTTP 500)").doesNotContain(TOKEN);
    }

    @Test
    void execucaoDaAuditoriaComDoisNovosGravesEnviaUmaMensagem() {
        var varredura = mock(VarreduraDaConta.class);
        when(varredura.executar()).thenReturn(new VarreduraDaConta.Resultado(List.of(), List.of()));
        var repositorio = mock(RepositorioDeAchados.class);
        when(repositorio.registrarComDetalhes(any(), any())).thenReturn(new Registro(new ResultadoDoDiff(3, 0, 0, 0, 0),
                List.of(achado("security-group/sg-1", "SECURITY_GROUP_ABERTO:22", Severidade.CRITICA),
                        achado("s3/b", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA),
                        achado("volume/v-1", "VOLUME_SEM_ANEXO", Severidade.MEDIA))));
        var auditoria = new AuditoriaService(varredura, repositorio, Clock.systemUTC(), notificador(TOKEN));

        var execucao = auditoria.executar();

        assertThat(execucao.diff().novos()).isEqualTo(3);
        telegram.verify(1, postRequestedFor(urlEqualTo(CAMINHO)));
    }

    @Test
    void passaDe10LinhasResumeEmUmaFraseSemCrescerSemLimite() {
        var muitos = new java.util.ArrayList<Achado>();
        for (int i = 0; i < 14; i++) {
            muitos.add(achado("security-group/sg-" + i, "SECURITY_GROUP_ABERTO:22", Severidade.CRITICA));
        }

        String texto = NotificadorTelegram.mensagem(muitos);

        assertThat(texto).contains("14 achado(s)").contains("... e mais 4.");
        assertThat(texto.lines().filter(l -> l.startsWith("- ["))).hasSize(10);
    }

    @Test
    void tokenComFormatoInvalidoNaoChamaOTelegramENaoApareceNoLog(CapturedOutput saida) {
        String tokenRuim = "token-sem-o-formato/../esperado";

        var enviado = notificador(tokenRuim).notificarNovosGraves(List.of(achado("s3/b", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA)));

        assertThat(enviado).isFalse();
        assertThat(telegram.getAllServeEvents()).isEmpty();
        assertThat(saida.getAll()).contains("token com formato inválido").doesNotContain(tokenRuim);
    }

    @Test
    void doisPontosDoTokenVaiLiteralNaUrlEnaoCodificado() {
        notificador(TOKEN).notificarNovosGraves(List.of(achado("s3/b", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS", Severidade.ALTA)));

        assertThat(telegram.getAllServeEvents().get(0).getRequest().getUrl()).isEqualTo(CAMINHO).doesNotContain("%3A");
    }
}
