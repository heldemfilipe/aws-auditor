package dev.heldem.aws_auditor.regras;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.VolumeEbs;

/** Volume EBS sem anexo há MAIS de N dias: paga armazenamento sem ninguém usar. Exatamente N dias ainda não é achado. */
@Component
public class VolumeSemAnexoRegra implements Regra {

    public static final String ID = "VOLUME_SEM_ANEXO";

    private final Clock clock;
    private final Duration limite;

    public VolumeSemAnexoRegra(Clock clock, @Value("${auditor.regras.volume-sem-anexo-dias:30}") int dias) {
        this.clock = clock;
        this.limite = Duration.ofDays(dias);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Achado> avaliar(Recurso recurso) {
        if (!(recurso instanceof VolumeEbs volume) || volume.anexado()) {
            return List.of();
        }
        var agora = clock.instant();
        var semAnexo = Duration.between(volume.semAnexoDesde(), agora);
        if (semAnexo.compareTo(limite) <= 0) {
            return List.of();
        }
        return List.of(Achado.aberto(ID, Severidade.MEDIA, volume.arn(),
                "Volume de %d GiB sem anexo há %d dias (limite: %d).".formatted(volume.tamanhoGib(), semAnexo.toDays(),
                        limite.toDays()),
                agora));
    }
}
