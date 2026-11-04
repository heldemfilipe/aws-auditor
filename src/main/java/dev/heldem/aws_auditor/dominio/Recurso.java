package dev.heldem.aws_auditor.dominio;

import java.util.Map;

/**
 * Um recurso da conta, já convertido do SDK para o domínio (o motor de regras nunca vê um tipo do SDK da AWS).
 *
 * <p>{@code sealed}: os tipos auditados são fechados. Quem faz um {@code switch} sobre {@code Recurso} sem tratar todos os
 * casos não compila, então acrescentar um tipo novo mostra, em tempo de compilação, tudo que precisa aprender sobre ele.
 */
public sealed interface Recurso permits VolumeEbs, BucketS3, ChaveIam, SecurityGroup {

    String arn();

    String regiao();

    Map<String, String> tags();

    /** O tipo, derivado do record. O {@code switch} é exaustivo: um tipo novo sem tratamento aqui não compila. */
    default TipoDeRecurso tipo() {
        return switch (this) {
            case VolumeEbs v -> TipoDeRecurso.VOLUME_EBS;
            case BucketS3 b -> TipoDeRecurso.BUCKET_S3;
            case ChaveIam c -> TipoDeRecurso.CHAVE_IAM;
            case SecurityGroup s -> TipoDeRecurso.SECURITY_GROUP;
        };
    }
}
