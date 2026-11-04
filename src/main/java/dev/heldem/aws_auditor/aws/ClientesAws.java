package dev.heldem.aws_auditor.aws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * Cria os clientes do SDK v2 com a cadeia padrão de credenciais, a região configurável e o retry configurado. O EC2 é por
 * região (um cliente por região, em cache); S3, IAM e STS são globais e usam a região padrão.
 */
@Component
public class ClientesAws {

    private final AwsProperties propriedades;
    private final AwsCredentialsProvider credenciais;
    private final Map<String, Ec2Client> ec2PorRegiao = new ConcurrentHashMap<>();
    private volatile S3Client s3;
    private volatile IamClient iam;
    private volatile StsClient sts;

    public ClientesAws(AwsProperties propriedades, AwsCredentialsProvider credenciais) {
        this.propriedades = propriedades;
        this.credenciais = credenciais;
    }

    /**
     * Retry do SDK: estratégia padrão (espera exponencial com jitter, e só para erros que valem repetir, como throttling e
     * 5xx), com o total de tentativas configurável. 1 tentativa = sem retry.
     */
    private ClientOverrideConfiguration sobrescritas() {
        var estrategia = propriedades.retryMaxTentativas() <= 1
                ? AwsRetryStrategy.doNotRetry()
                : AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(propriedades.retryMaxTentativas()).build();
        return ClientOverrideConfiguration.builder().retryStrategy(estrategia).build();
    }

    private <B extends AwsClientBuilder<B, C>, C> C configurar(B construtor, String regiao) {
        construtor.region(Region.of(regiao)).credentialsProvider(credenciais).overrideConfiguration(sobrescritas());
        if (propriedades.endpoint() != null) {
            construtor.endpointOverride(propriedades.endpoint());
        }
        return construtor.build();
    }

    public Ec2Client ec2(String regiao) {
        return ec2PorRegiao.computeIfAbsent(regiao, r -> configurar(Ec2Client.builder(), r));
    }

    public synchronized S3Client s3() {
        if (s3 == null) {
            var construtor = S3Client.builder().crossRegionAccessEnabled(true);
            if (propriedades.endpoint() != null) {
                construtor.forcePathStyle(true);
            }
            s3 = configurar(construtor, propriedades.regiaoPadrao());
        }
        return s3;
    }

    public synchronized IamClient iam() {
        if (iam == null) {
            iam = configurar(IamClient.builder(), propriedades.regiaoPadrao());
        }
        return iam;
    }

    public synchronized StsClient sts() {
        if (sts == null) {
            sts = configurar(StsClient.builder(), propriedades.regiaoPadrao());
        }
        return sts;
    }
}
