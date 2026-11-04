package dev.heldem.aws_auditor.api;

import java.time.Instant;

import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.StatusAchado;
import dev.heldem.aws_auditor.persistencia.AchadoItem;

record AchadoResponse(String id, String regra, Severidade severidade, String arn, String descricao, StatusAchado status,
        Instant primeiraVezVisto, Instant ultimaVezVisto, Instant resolvidoEm, Instant ignoradoAte, String justificativa) {

    static AchadoResponse de(AchadoItem i) {
        return new AchadoResponse(new IdDoAchado(i.getArn(), i.getRegra()).codificar(), i.getRegra(), i.getSeveridade(),
                i.getArn(), i.getDescricao(), i.getStatus(), Instant.ofEpochMilli(i.getPrimeiraVezVisto()),
                Instant.ofEpochMilli(i.getUltimaVezVisto()), instante(i.getResolvidoEm()), instante(i.getIgnoradoAte()),
                i.getJustificativa());
    }

    private static Instant instante(Long ms) {
        return ms == null ? null : Instant.ofEpochMilli(ms);
    }
}
