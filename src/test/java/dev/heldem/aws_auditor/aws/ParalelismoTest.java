package dev.heldem.aws_auditor.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.motor.MotorDeRegras;
import dev.heldem.aws_auditor.regras.SecurityGroupAbertoRegra;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Varredura de várias regiões, em sequência e em paralelo, contra um servidor HTTP local que faz o papel da AWS (STS e EC2)
 * com uma LATÊNCIA fixa por chamada de EC2, como a rede de verdade teria. O cliente é o real do SDK v2. Mede o tempo, confere
 * que o resultado é o mesmo e que as métricas do Micrometer saem certas.
 */
class ParalelismoTest {

    static final int REGIOES = 12;
    static final long LATENCIA_MS = 150;

    static final String STS = """
            <GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>
            <Arn>arn:aws:iam::123456789012:user/sintetico</Arn><UserId>AIDASINTETICO</UserId><Account>123456789012</Account>
            </GetCallerIdentityResult><ResponseMetadata><RequestId>r</RequestId></ResponseMetadata></GetCallerIdentityResponse>""";

    static final String VOLUMES = """
            <DescribeVolumesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"><requestId>r</requestId>
            <volumeSet><item><volumeId>vol-0a1</volumeId><size>10</size><status>available</status>
            <createTime>2026-01-10T00:00:00.000Z</createTime><attachmentSet/></item></volumeSet></DescribeVolumesResponse>""";

    static final String SG_VAZIO = """
            <DescribeSecurityGroupsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"><requestId>r</requestId>
            <securityGroupInfo/></DescribeSecurityGroupsResponse>""";

    static final String SG_COM_SSH_ABERTO = """
            <DescribeSecurityGroupsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"><requestId>r</requestId>
            <securityGroupInfo><item><groupId>sg-0aa</groupId><ipPermissions><item><ipProtocol>tcp</ipProtocol>
            <fromPort>22</fromPort><toPort>22</toPort><ipRanges><item><cidrIp>0.0.0.0/0</cidrIp></item></ipRanges>
            </item></ipPermissions></item></securityGroupInfo></DescribeSecurityGroupsResponse>""";

    static final String NAO_AUTORIZADO = """
            <Response><Errors><Error><Code>UnauthorizedOperation</Code><Message>Sem permissao.</Message></Error></Errors>
            <RequestID>r</RequestID></Response>""";

    HttpServer servidor;
    final AtomicInteger simultaneasAgora = new AtomicInteger();
    final AtomicInteger maximoSimultaneo = new AtomicInteger();

    /** @param volumesNegados responde 403 ao DescribeVolumes; @param sgAberto security group com SSH aberto */
    void subirServidor(boolean volumesNegados, boolean sgAberto) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.setExecutor(Executors.newCachedThreadPool()); // o servidor atende várias chamadas ao mesmo tempo
        servidor.createContext("/", troca -> {
            String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String resposta;
            if (corpo.contains("Action=GetCallerIdentity")) {
                resposta = STS;
            } else {
                int agora = simultaneasAgora.incrementAndGet();
                maximoSimultaneo.accumulateAndGet(agora, Math::max);
                try {
                    Thread.sleep(LATENCIA_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    simultaneasAgora.decrementAndGet();
                }
                if (corpo.contains("Action=DescribeVolumes")) {
                    status = volumesNegados ? 403 : 200;
                    resposta = volumesNegados ? NAO_AUTORIZADO : VOLUMES;
                } else {
                    resposta = sgAberto ? SG_COM_SSH_ABERTO : SG_VAZIO;
                }
            }
            byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "text/xml");
            troca.sendResponseHeaders(status, bytes.length);
            troca.getResponseBody().write(bytes);
            troca.close();
        });
        servidor.start();
    }

    VarreduraDaConta varredura(boolean paralela, int teto, SimpleMeterRegistry metricas) {
        var regioes = IntStream.rangeClosed(1, REGIOES).mapToObj(i -> "regiao-%02d".formatted(i)).toList();
        var propriedades = new AwsProperties(regioes, "us-east-1", 1,
                URI.create("http://127.0.0.1:" + servidor.getAddress().getPort())); // 1 tentativa: sem retry
        var credenciais = StaticCredentialsProvider.create(AwsBasicCredentials.create("chave-de-teste", "segredo-de-teste"));
        var clientes = new ClientesAws(propriedades, credenciais);
        var motor = new MotorDeRegras(List.of(new SecurityGroupAbertoRegra(Clock.systemUTC())));
        return new VarreduraDaConta(clientes, propriedades, new VarreduraProperties(paralela, teto), List.of(),
                List.of(new ColetorDeVolumes(clientes), new ColetorDeSecurityGroups(clientes)), motor, metricas);
    }

    @AfterEach
    void parar() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    static long medirMs(Runnable acao) {
        long inicio = System.nanoTime();
        acao.run();
        return (System.nanoTime() - inicio) / 1_000_000;
    }

    @Test
    void paraleloTerminaMuitoMaisRapidoComOMesmoResultadoEOTetoLimitaOGanho() throws IOException {
        subirServidor(false, false);
        var sequencial = varredura(false, 16, new SimpleMeterRegistry());
        var paralelaCom16 = varredura(true, 16, new SimpleMeterRegistry());
        var paralelaCom4 = varredura(true, 4, new SimpleMeterRegistry());

        sequencial.executar(); // aquecimento: criação dos clientes, classes, conexões (não entra na medida)
        paralelaCom16.executar();

        var tSeq = new ArrayList<Long>();
        var t16 = new ArrayList<Long>();
        var t4 = new ArrayList<Long>();
        List<Recurso> recursosSeq = null;
        List<Recurso> recursosPar = null;
        for (int i = 0; i < 3; i++) {
            tSeq.add(medirMs(() -> sequencial.executar()));
            maximoSimultaneo.set(0);
            t16.add(medirMs(() -> paralelaCom16.executar()));
            t4.add(medirMs(() -> paralelaCom4.executar()));
        }
        recursosSeq = sequencial.executar().recursos();
        recursosPar = paralelaCom16.executar().recursos();

        System.out.printf("PARALELISMO: %d regiões x 2 coletores = %d chamadas de %d ms cada%n", REGIOES, REGIOES * 2,
                LATENCIA_MS);
        System.out.printf("PARALELISMO: sequencial %s ms | paralela (teto 16) %s ms | paralela (teto 4) %s ms%n", tSeq, t16, t4);
        System.out.println("PARALELISMO: máximo de chamadas simultâneas observado no servidor = " + maximoSimultaneo.get());

        assertThat(recursosPar).isEqualTo(recursosSeq); // mesmo resultado, na mesma ordem
        assertThat(recursosSeq).hasSize(REGIOES); // um volume por região
        long mSeq = tSeq.stream().sorted().toList().get(1);
        long m16 = t16.stream().sorted().toList().get(1);
        long m4 = t4.stream().sorted().toList().get(1);
        assertThat(m16).as("paralela com teto 16 bem mais rápida que a sequencial").isLessThan(mSeq / 3);
        assertThat(m4).as("teto menor, ganho menor").isBetween(m16, mSeq);
        assertThat(maximoSimultaneo.get()).isLessThanOrEqualTo(16);
    }

    @Test
    void metricasDeDuracaoAchadosPorSeveridadeEErrosPorServico() throws IOException {
        subirServidor(false, true);
        var metricas = new SimpleMeterRegistry();

        var resultado = varredura(true, 16, metricas).executar();

        assertThat(resultado.achados()).hasSize(REGIOES); // SSH aberto, um por região
        assertThat(metricas.get("auditor.varredura.duracao").tag("paralela", "true").timer().count()).isEqualTo(1);
        assertThat(metricas.get("auditor.achados").tag("severidade", "CRITICA").gauge().value()).isEqualTo(REGIOES);
        assertThat(metricas.get("auditor.achados").tag("severidade", "BAIXA").gauge().value()).isZero();
    }

    @Test
    void falhaDeUmServicoViraErroPorServicoEAVarreduraFalhaEmVezDeEntregarResultadoParcial() throws IOException {
        subirServidor(true, false); // o EC2 nega o DescribeVolumes (403) em todas as regiões
        var metricas = new SimpleMeterRegistry();

        assertThatThrownBy(() -> varredura(true, 16, metricas).executar()).isInstanceOf(VarreduraFalhouException.class)
                .satisfies(e -> assertThat(((VarreduraFalhouException) e).falhas()).hasSize(REGIOES))
                .hasMessageContaining("Sem permissao");

        assertThat(metricas.get("auditor.varredura.erros").tag("servico", "ec2").counter().count()).isEqualTo(REGIOES);
        assertThat(metricas.get("auditor.varredura.duracao").timer().count()).isEqualTo(1); // mede também a que falhou
    }
}
