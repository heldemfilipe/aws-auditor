package dev.heldem.aws_auditor.notificacao;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import dev.heldem.aws_auditor.dominio.Achado;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Avisa no Telegram dos achados NOVOS de severidade ALTA ou mais, em UMA mensagem por execução (e não uma por achado). Sem
 * achado novo grave, não manda nada. Uma falha do aviso nunca derruba a auditoria: vira log e o contador
 * {@code auditor.notificacao.erros}.
 *
 * <p>O token do bot vai na URL da API do Telegram, então ele NUNCA é logado (nem o URL, nem a mensagem de erro do cliente
 * HTTP, que o incluiria): só o tipo da exceção e o status.
 */
@Component
@EnableConfigurationProperties(TelegramProperties.class)
public class NotificadorTelegram {

    private static final Logger log = LoggerFactory.getLogger(NotificadorTelegram.class);
    private static final int MAXIMO_DE_LINHAS = 10;

    private final TelegramProperties propriedades;
    private final RestClient http;
    private final MeterRegistry metricas;

    public NotificadorTelegram(TelegramProperties propriedades, MeterRegistry metricas) {
        this.propriedades = propriedades;
        this.metricas = metricas;
        this.http = RestClient.create(propriedades.baseUrl());
    }

    /** @return true se a mensagem foi enviada */
    public boolean notificarNovosGraves(List<Achado> novos) {
        List<Achado> graves = novos.stream().filter(a -> a.severidade().ehAltaOuMais()).toList();
        if (graves.isEmpty()) {
            return false;
        }
        if (!propriedades.ligado()) {
            log.info("{} achado(s) novo(s) grave(s), mas o aviso no Telegram está desligado (sem token ou chat).", graves.size());
            return false;
        }
        if (!propriedades.tokenComFormatoValido()) {
            // o token não é mostrado: só se diz que o formato está errado
            registrarFalha("token com formato inválido");
            return false;
        }
        try {
            // O ":" do token tem que ir LITERAL no caminho (o Telegram o espera assim). Um template de URI o codificaria como
            // %3A, então o URI é montado direto; o formato do token acima garante que só há caracteres seguros nele.
            URI uri = URI.create(propriedades.baseUrl() + "/bot" + propriedades.token() + "/sendMessage");
            http.post().uri(uri)
                    .body(Map.of("chat_id", propriedades.chatId(), "text", mensagem(graves))).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            registrarFalha("HTTP " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            registrarFalha(e.getClass().getSimpleName()); // sem a mensagem: ela pode conter a URL com o token
        }
        return false;
    }

    private void registrarFalha(String motivo) {
        metricas.counter("auditor.notificacao.erros").increment();
        log.warn("Aviso no Telegram falhou ({}). A auditoria seguiu.", motivo);
    }

    /** Texto puro (sem parse_mode): descrições vêm da conta e não podem injetar formatação. */
    static String mensagem(List<Achado> graves) {
        var texto = new StringBuilder("aws-auditor: %d achado(s) novo(s) de severidade alta ou crítica:%n"
                .formatted(graves.size()));
        graves.stream().limit(MAXIMO_DE_LINHAS).forEach(a -> texto.append("- [%s] %s: %s%n".formatted(a.severidade(),
                a.regra(), a.arn())));
        if (graves.size() > MAXIMO_DE_LINHAS) {
            texto.append("... e mais %d.".formatted(graves.size() - MAXIMO_DE_LINHAS));
        }
        return texto.toString().stripTrailing();
    }
}
