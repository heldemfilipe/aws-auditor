package dev.heldem.aws_auditor.motor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.BucketS3;
import dev.heldem.aws_auditor.dominio.BucketS3.BloqueioDeAcessoPublico;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.SecurityGroup;
import dev.heldem.aws_auditor.dominio.SecurityGroup.RegraDeEntrada;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.TipoDeRecurso;
import dev.heldem.aws_auditor.dominio.VolumeEbs;
import dev.heldem.aws_auditor.regras.Regra;

/** O motor com as regras injetadas pelo Spring, e a garantia de que o domínio, as regras e o motor não conhecem o SDK. */
@SpringBootTest
@Import(MotorDeRegrasTest.RegraExtra.class)
class MotorDeRegrasTest {

    /** Uma regra "nova": só um bean, o motor não é tocado. */
    @TestConfiguration
    static class RegraExtra {
        @Bean
        Regra regraDeTeste() {
            return new Regra() {
                @Override
                public String id() {
                    return "REGRA_EXTRA_DE_TESTE";
                }

                @Override
                public List<Achado> avaliar(Recurso recurso) {
                    return recurso instanceof BucketS3 b
                            ? List.of(Achado.aberto(id(), Severidade.BAIXA, b.arn(), "extra", Instant.EPOCH))
                            : List.of();
                }
            };
        }
    }

    @Autowired
    MotorDeRegras motor;

    @Test
    void regrasChegamPeloSpringInclusiveUmaRegraNovaSemAlterarOMotor() {
        assertThat(motor.idsDasRegras()).containsExactlyInAnyOrder("VOLUME_SEM_ANEXO", "BUCKET_SEM_BLOCK_PUBLIC_ACCESS",
                "CHAVE_IAM_PARADA", "SECURITY_GROUP_ABERTO", "REGRA_EXTRA_DE_TESTE");
    }

    @Test
    void achadosVemDoMaisGraveParaOMenosGraveEDeFormaEstavel() {
        var bucket = new BucketS3("arn:aws:s3:::b-sintetico", "us-east-1", Map.of(), BloqueioDeAcessoPublico.nenhum());
        var sg = new SecurityGroup("arn:aws:ec2:us-east-1:123456789012:security-group/sg-0b2", "us-east-1", Map.of(),
                List.of(new RegraDeEntrada("tcp", 22, 22, "0.0.0.0/0")));
        var volumeEmUso = new VolumeEbs("arn:aws:ec2:us-east-1:123456789012:volume/vol-1", "us-east-1", Map.of(), 8, null);

        List<Achado> achados = motor.auditar(List.of(volumeEmUso, bucket, sg));

        assertThat(achados).extracting(Achado::severidade).containsExactly(Severidade.CRITICA, Severidade.ALTA,
                Severidade.BAIXA);
        // a ordem de entrada não importa (o instante muda a cada chamada, então comparo regra e recurso)
        assertThat(motor.auditar(List.of(sg, bucket, volumeEmUso))).extracting(Achado::regra, Achado::arn)
                .isEqualTo(achados.stream().map(a -> org.assertj.core.groups.Tuple.tuple(a.regra(), a.arn())).toList());
    }

    @Test
    void cadaRecursoTemSeuTipo() {
        assertThat(new VolumeEbs("a", "r", Map.of(), 1, null).tipo()).isEqualTo(TipoDeRecurso.VOLUME_EBS);
        assertThat(new BucketS3("a", "r", Map.of(), BloqueioDeAcessoPublico.completo()).tipo())
                .isEqualTo(TipoDeRecurso.BUCKET_S3);
    }

    @Test
    void dominioRegrasEMotorNaoImportamOSdkDaAws() throws IOException {
        for (String pacote : List.of("dominio", "regras", "motor")) {
            Path pasta = Path.of("src/main/java/dev/heldem/aws_auditor", pacote);
            try (Stream<Path> arquivos = Files.walk(pasta)) {
                for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                    assertThat(Files.readString(arquivo)).as("%s não pode importar o SDK da AWS", arquivo)
                            .doesNotContain("software.amazon.awssdk");
                }
            }
        }
    }
}
