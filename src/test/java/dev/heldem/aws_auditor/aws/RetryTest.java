package dev.heldem.aws_auditor.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.heldem.aws_auditor.dominio.VolumeEbs;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

/**
 * Throttling de verdade, sem AWS: um servidor HTTP local faz o papel do EC2 e devolve "RequestLimitExceeded" (HTTP 503, o
 * erro de throttling do EC2) nas duas primeiras chamadas. O cliente é o REAL do SDK v2 apontado para ele ({@code endpoint}),
 * então o retry exercitado é o do SDK, não um stub nosso. As credenciais são valores de mentira, só para assinar a chamada.
 */
class RetryTest {

    static final String VOLUME_XML = """
            <DescribeVolumesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
              <requestId>req-1</requestId>
              <volumeSet><item><volumeId>vol-0a1</volumeId><size>10</size><status>available</status>
                <createTime>2026-01-10T00:00:00.000Z</createTime><attachmentSet/></item></volumeSet>
            </DescribeVolumesResponse>""";

    static final String THROTTLING_XML = """
            <Response><Errors><Error><Code>RequestLimitExceeded</Code><Message>Request limit exceeded.</Message></Error></Errors>
            <RequestID>req-2</RequestID></Response>""";

    HttpServer servidor;
    final AtomicInteger chamadas = new AtomicInteger();

    /** Sobe o servidor que devolve throttling nas primeiras {@code falhas} chamadas e depois um volume. */
    ColetorDeVolumes coletorComThrottling(int falhas, int tentativas) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            troca.getRequestBody().readAllBytes();
            boolean falha = chamadas.incrementAndGet() <= falhas;
            byte[] corpo = (falha ? THROTTLING_XML : VOLUME_XML).getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "text/xml");
            troca.sendResponseHeaders(falha ? 503 : 200, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();
        var propriedades = new AwsProperties(null, "us-east-1", tentativas,
                URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()));
        var credenciaisDeMentira = StaticCredentialsProvider.create(AwsBasicCredentials.create("chave-de-teste", "segredo-de-teste"));
        return new ColetorDeVolumes(new ClientesAws(propriedades, credenciaisDeMentira));
    }

    @AfterEach
    void parar() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    @Test
    void comRetryASdkRepeteEAVarreduraTermina() throws IOException {
        var coletor = coletorComThrottling(2, 3);

        long inicio = System.nanoTime();
        var recursos = coletor.coletar("123456789012", "us-east-1");
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(chamadas.get()).as("2 throttlings + 1 sucesso").isEqualTo(3);
        assertThat(recursos).singleElement().satisfies(r -> assertThat(((VolumeEbs) r).tamanhoGib()).isEqualTo(10));
        System.out.println("RETRY: 3 chamadas em " + ms + " ms (espera crescente com jitter entre elas)");
    }

    @Test
    void semRetryAVarreduraFalhaComOErroDeThrottlingVisivel() throws IOException {
        var coletor = coletorComThrottling(2, 1);

        assertThatThrownBy(() -> coletor.coletar("123456789012", "us-east-1")).isInstanceOf(Ec2Exception.class)
                .hasMessageContaining("Request limit exceeded");
        assertThat(chamadas.get()).as("uma única tentativa").isEqualTo(1);
    }

    @Test
    void retryTemLimiteSeOThrottlingNaoPassaAVarreduraFalha() throws IOException {
        var coletor = coletorComThrottling(10, 3);

        assertThatThrownBy(() -> coletor.coletar("123456789012", "us-east-1")).isInstanceOf(Ec2Exception.class);
        assertThat(chamadas.get()).as("desiste depois das 3 tentativas").isEqualTo(3);
    }
}
