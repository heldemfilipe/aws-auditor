package dev.heldem.aws_auditor.regras;

import java.util.List;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Recurso;

/**
 * Uma regra de auditoria. Pura: recebe um recurso do domínio e devolve achados, sem tocar na AWS, no banco ou na rede. O
 * motor recebe TODAS as regras pelo Spring ({@code List<Regra>}), então uma regra nova é só um {@code @Component}, sem
 * alterar o motor.
 */
public interface Regra {

    /** Identificador estável, usado na chave do achado (ex.: "VOLUME_SEM_ANEXO"). */
    String id();

    /** Os achados deste recurso; lista vazia se a regra não se aplica ao tipo ou o recurso está em ordem. */
    List<Achado> avaliar(Recurso recurso);
}
