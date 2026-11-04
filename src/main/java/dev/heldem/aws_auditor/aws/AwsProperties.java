package dev.heldem.aws_auditor.aws;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do acesso à AWS. As credenciais NÃO estão aqui: vêm da cadeia padrão do SDK (variáveis de ambiente, perfil,
 * role da tarefa...), nunca do código nem do repositório.
 *
 * @param regioes regiões varridas (para volumes e security groups; S3 e IAM são varridos uma vez só)
 * @param regiaoPadrao região dos clientes globais (S3, IAM, STS)
 * @param retryMaxTentativas total de tentativas por chamada (1 = sem retry)
 * @param endpoint só para testes e execução local contra um servidor falso; em produção fica vazio
 */
@ConfigurationProperties("auditor.aws")
public record AwsProperties(List<String> regioes, String regiaoPadrao, int retryMaxTentativas, URI endpoint) {

    public AwsProperties {
        regioes = regioes == null || regioes.isEmpty() ? List.of("us-east-1") : List.copyOf(regioes);
        regiaoPadrao = regiaoPadrao == null || regiaoPadrao.isBlank() ? "us-east-1" : regiaoPadrao;
        retryMaxTentativas = retryMaxTentativas <= 0 ? 3 : retryMaxTentativas;
    }
}
