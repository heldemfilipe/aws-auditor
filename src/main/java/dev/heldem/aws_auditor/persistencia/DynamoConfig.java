package dev.heldem.aws_auditor.persistencia;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.heldem.aws_auditor.aws.AwsProperties;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@Configuration
class DynamoConfig {

    /** {@code auditor.dynamodb.endpoint} só existe para rodar contra o DynamoDB Local; em produção fica vazio. */
    @Bean
    DynamoDbClient dynamoDbClient(AwsProperties aws, AwsCredentialsProvider credenciais,
            @Value("${auditor.dynamodb.endpoint:}") String endpoint) {
        DynamoDbClientBuilder construtor = DynamoDbClient.builder().region(Region.of(aws.regiaoPadrao()))
                .credentialsProvider(credenciais);
        if (!endpoint.isBlank()) {
            construtor.endpointOverride(URI.create(endpoint));
        }
        return construtor.build();
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient cliente) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(cliente).build();
    }

    @Bean
    DynamoDbTable<AchadoItem> tabelaDeAchados(DynamoDbEnhancedClient enhanced,
            @Value("${auditor.dynamodb.tabela:achados}") String tabela) {
        return enhanced.table(tabela, TableSchema.fromBean(AchadoItem.class));
    }
}
