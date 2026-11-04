package dev.heldem.aws_auditor.dominio;

import java.time.Instant;

/**
 * Um problema encontrado em um recurso por uma regra. A identidade é o par (arn, regra): o mesmo recurso violando a mesma
 * regra em duas execuções é o MESMO achado.
 *
 * @param primeiraVezVisto quando este achado apareceu pela primeira vez
 */
public record Achado(String regra, Severidade severidade, String arn, String descricao, Instant primeiraVezVisto,
        StatusAchado status) {

    public static Achado aberto(String regra, Severidade severidade, String arn, String descricao, Instant agora) {
        return new Achado(regra, severidade, arn, descricao, agora, StatusAchado.ABERTO);
    }
}
