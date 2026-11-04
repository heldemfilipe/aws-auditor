package dev.heldem.aws_auditor.aws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

@Configuration
@EnableConfigurationProperties({ AwsProperties.class, VarreduraProperties.class })
class AwsConfig {

    /**
     * A cadeia padrão de credenciais do SDK. É resolvida de forma PREGUIÇOSA (na primeira chamada à AWS), então a aplicação
     * sobe sem credenciais. Nunca há chave no código, no application.properties nem no repositório.
     */
    @Bean
    AwsCredentialsProvider credenciaisAws() {
        return DefaultCredentialsProvider.builder().build();
    }
}
