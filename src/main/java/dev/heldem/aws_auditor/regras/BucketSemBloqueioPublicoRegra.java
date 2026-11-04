package dev.heldem.aws_auditor.regras;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.BucketS3;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.Severidade;

/** Bucket S3 sem o "block public access" completo (as quatro proteções): um erro de ACL ou de policy o expõe à internet. */
@Component
public class BucketSemBloqueioPublicoRegra implements Regra {

    public static final String ID = "BUCKET_SEM_BLOCK_PUBLIC_ACCESS";

    private final Clock clock;

    public BucketSemBloqueioPublicoRegra(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Achado> avaliar(Recurso recurso) {
        if (!(recurso instanceof BucketS3 bucket) || bucket.bloqueioDeAcessoPublico().estaCompleto()) {
            return List.of();
        }
        return List.of(Achado.aberto(ID, Severidade.ALTA, bucket.arn(),
                "Bucket sem o block public access completo (alguma das quatro proteções está desligada).",
                clock.instant()));
    }
}
