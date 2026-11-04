package dev.heldem.aws_auditor.dominio;

import java.time.Instant;
import java.util.Map;

/**
 * @param ultimoUso quando a chave foi usada pela última vez; {@code null} se nunca foi usada
 */
public record ChaveIam(String arn, String regiao, Map<String, String> tags, String usuario, boolean ativa, Instant criadaEm,
        Instant ultimoUso) implements Recurso {

    public ChaveIam {
        tags = Map.copyOf(tags);
    }
}
