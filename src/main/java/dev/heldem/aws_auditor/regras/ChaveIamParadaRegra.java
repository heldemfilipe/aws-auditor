package dev.heldem.aws_auditor.regras;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.ChaveIam;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.Severidade;

/**
 * Chave de acesso IAM ATIVA sem uso há mais de N dias (padrão 90). Chave que nunca foi usada conta a partir da criação. Chave
 * inativa não é achado: já está desligada.
 */
@Component
public class ChaveIamParadaRegra implements Regra {

    public static final String ID = "CHAVE_IAM_PARADA";

    private final Clock clock;
    private final Duration limite;

    public ChaveIamParadaRegra(Clock clock, @Value("${auditor.regras.chave-iam-dias:90}") int dias) {
        this.clock = clock;
        this.limite = Duration.ofDays(dias);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Achado> avaliar(Recurso recurso) {
        if (!(recurso instanceof ChaveIam chave) || !chave.ativa()) {
            return List.of();
        }
        Instant desde = chave.ultimoUso() != null ? chave.ultimoUso() : chave.criadaEm();
        var agora = clock.instant();
        var parada = Duration.between(desde, agora);
        if (parada.compareTo(limite) <= 0) {
            return List.of();
        }
        String como = chave.ultimoUso() != null ? "sem uso" : "nunca usada, criada";
        return List.of(Achado.aberto(ID, Severidade.MEDIA, chave.arn(),
                "Chave ativa do usuário '%s' %s há %d dias (limite: %d).".formatted(chave.usuario(), como,
                        parada.toDays(), limite.toDays()),
                agora));
    }
}
