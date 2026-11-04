package dev.heldem.aws_auditor.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Protege TUDO (a API de achados e as métricas) com uma chave no cabeçalho {@code X-Api-Key}, lida da variável de ambiente
 * {@code AUDITOR_API_KEY}. Só a saúde ({@code /actuator/health}) é pública, para o orquestrador.
 *
 * <ul>
 *   <li><b>Comparação em tempo constante:</b> {@link MessageDigest#isEqual} sobre os SHA-256 das duas chaves. O hash
 *       primeiro, porque {@code isEqual} compara em tempo constante só para o mesmo tamanho, e o tamanho da chave
 *       verdadeira não deve vazar.</li>
 *   <li><b>Falha fechada:</b> sem chave configurada, ninguém entra (nenhuma chave "vazia" é aceita).</li>
 *   <li><b>401 antes de 404:</b> um caminho que não existe também pede a chave, então não dá para descobrir rotas sem ela.</li>
 * </ul>
 *
 * <p>Escolhi chave em cabeçalho (e não JWT) porque este projeto não tem provedor de identidade: é uma ferramenta de um
 * operador, e uma chave rotacionável por variável de ambiente basta. Com Keycloak (como no room-booking-api), seria JWT.
 */
@Component
class ChaveDeApiFilter extends OncePerRequestFilter {

    static final String CABECALHO = "X-Api-Key";
    private static final Logger log = LoggerFactory.getLogger(ChaveDeApiFilter.class);

    private final byte[] resumoDaChave;

    ChaveDeApiFilter(@Value("${auditor.api.chave:}") String chave) {
        this.resumoDaChave = chave.isBlank() ? null : sha256(chave);
        if (resumoDaChave == null) {
            log.warn("AUDITOR_API_KEY não definida: a API e as métricas recusarão TODAS as requisições (falha fechada).");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        return caminho.equals("/actuator/health") || caminho.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String enviada = requisicao.getHeader(CABECALHO);
        if (resumoDaChave != null && enviada != null && MessageDigest.isEqual(resumoDaChave, sha256(enviada))) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }
        resposta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resposta.setContentType("application/problem+json");
        resposta.getWriter().write("{\"title\":\"Não autorizado\",\"status\":401,\"detail\":\"Chave de API ausente ou inválida.\"}");
    }

    private static byte[] sha256(String texto) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não está disponível", e);
        }
    }
}
