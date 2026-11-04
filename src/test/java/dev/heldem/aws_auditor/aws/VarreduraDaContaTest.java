package dev.heldem.aws_auditor.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.heldem.aws_auditor.dominio.BucketS3;
import dev.heldem.aws_auditor.dominio.BucketS3.BloqueioDeAcessoPublico;
import dev.heldem.aws_auditor.dominio.SecurityGroup;
import dev.heldem.aws_auditor.dominio.SecurityGroup.RegraDeEntrada;
import dev.heldem.aws_auditor.motor.MotorDeRegras;
import dev.heldem.aws_auditor.regras.BucketSemBloqueioPublicoRegra;
import dev.heldem.aws_auditor.regras.SecurityGroupAbertoRegra;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

/** A varredura junta os coletores ao motor: globais UMA vez, regionais uma vez por região, e as regras sobre tudo. */
class VarreduraDaContaTest {

    @Test
    void globaisUmaVezRegionaisPorRegiaoEOMotorSobreTudo() {
        var clientes = mock(ClientesAws.class);
        var sts = mock(StsClient.class);
        when(clientes.sts()).thenReturn(sts);
        when(sts.getCallerIdentity(any(GetCallerIdentityRequest.class)))
                .thenReturn(GetCallerIdentityResponse.builder().account("123456789012").build());

        var global = mock(ColetorGlobal.class);
        when(global.servico()).thenReturn("s3");
        when(global.coletar("123456789012")).thenReturn(List
                .of(new BucketS3("arn:aws:s3:::aberto", "us-east-1", Map.of(), BloqueioDeAcessoPublico.nenhum())));
        var regional = mock(ColetorRegional.class);
        when(regional.servico()).thenReturn("ec2");
        when(regional.coletar(any(), any())).thenAnswer(i -> List.of(new SecurityGroup(
                "arn:aws:ec2:" + i.getArgument(1) + ":123456789012:security-group/sg-1", i.getArgument(1), Map.of(),
                List.of(new RegraDeEntrada("tcp", 22, 22, "0.0.0.0/0")))));

        var relogio = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
        var motor = new MotorDeRegras(List.of(new BucketSemBloqueioPublicoRegra(relogio), new SecurityGroupAbertoRegra(relogio)));
        var propriedades = new AwsProperties(List.of("us-east-1", "sa-east-1"), "us-east-1", 3, null);

        var resultado = new VarreduraDaConta(clientes, propriedades, new VarreduraProperties(false, 16), List.of(global),
                List.of(regional), motor, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).executar();

        verify(global, times(1)).coletar("123456789012");
        verify(regional).coletar("123456789012", "us-east-1");
        verify(regional).coletar("123456789012", "sa-east-1");
        assertThat(resultado.recursos()).hasSize(3); // 1 bucket + 1 security group por região
        assertThat(resultado.achados()).hasSize(3);
        assertThat(resultado.achados().get(0).regra()).isEqualTo("SECURITY_GROUP_ABERTO:22"); // crítico primeiro
    }
}
