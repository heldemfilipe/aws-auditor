package dev.heldem.aws_auditor.aws;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.BucketS3;
import dev.heldem.aws_auditor.dominio.BucketS3.BloqueioDeAcessoPublico;
import dev.heldem.aws_auditor.dominio.Recurso;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Buckets S3 e o estado do block public access de cada um. Um bucket SEM configuração de block public access devolve o erro
 * {@code NoSuchPublicAccessBlockConfiguration}: isso NÃO é falha da varredura, é o achado (nenhuma proteção ligada).
 */
@Component
public class ColetorDeBuckets implements ColetorGlobal {

    private final ClientesAws clientes;

    public ColetorDeBuckets(ClientesAws clientes) {
        this.clientes = clientes;
    }

    @Override
    public List<String> acoesIam() {
        return List.of("s3:ListAllMyBuckets", "s3:GetBucketPublicAccessBlock");
    }

    @Override
    public List<Recurso> coletar(String contaId) {
        var s3 = clientes.s3();
        return s3.listBucketsPaginator(ListBucketsRequest.builder().build()).buckets().stream()
                .<Recurso>map(bucket -> new BucketS3("arn:aws:s3:::" + bucket.name(), regiaoDe(bucket), Map.of(),
                        bloqueioDe(bucket.name())))
                .toList();
    }

    private String regiaoDe(Bucket bucket) {
        return bucket.bucketRegion() == null ? "desconhecida" : bucket.bucketRegion();
    }

    private BloqueioDeAcessoPublico bloqueioDe(String bucket) {
        try {
            PublicAccessBlockConfiguration c = clientes.s3()
                    .getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket(bucket).build())
                    .publicAccessBlockConfiguration();
            return new BloqueioDeAcessoPublico(Boolean.TRUE.equals(c.blockPublicAcls()),
                    Boolean.TRUE.equals(c.ignorePublicAcls()), Boolean.TRUE.equals(c.blockPublicPolicy()),
                    Boolean.TRUE.equals(c.restrictPublicBuckets()));
        } catch (S3Exception e) {
            if (e.awsErrorDetails() != null
                    && "NoSuchPublicAccessBlockConfiguration".equals(e.awsErrorDetails().errorCode())) {
                return BloqueioDeAcessoPublico.nenhum();
            }
            throw e;
        }
    }
}
