package dev.heldem.aws_auditor.regras;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.BucketS3;
import dev.heldem.aws_auditor.dominio.BucketS3.BloqueioDeAcessoPublico;
import dev.heldem.aws_auditor.dominio.ChaveIam;
import dev.heldem.aws_auditor.dominio.SecurityGroup;
import dev.heldem.aws_auditor.dominio.SecurityGroup.RegraDeEntrada;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.StatusAchado;
import dev.heldem.aws_auditor.dominio.VolumeEbs;

/** As quatro regras, com dados sintéticos (conta fictícia 123456789012): caso positivo, negativo e de borda. */
class RegrasTest {

    static final Instant AGORA = Instant.parse("2026-11-04T12:00:00Z");
    static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);
    static final Map<String, String> SEM_TAGS = Map.of();

    static Instant haDias(long dias) {
        return AGORA.minus(Duration.ofDays(dias));
    }

    @Nested
    class VolumeSemAnexo {

        final VolumeSemAnexoRegra regra = new VolumeSemAnexoRegra(RELOGIO, 30);

        VolumeEbs volume(Instant semAnexoDesde) {
            return new VolumeEbs("arn:aws:ec2:us-east-1:123456789012:volume/vol-0a1", "us-east-1", SEM_TAGS, 100, semAnexoDesde);
        }

        @Test
        void semAnexoHaMaisDeNDiasEAchado() {
            List<Achado> achados = regra.avaliar(volume(haDias(45)));

            assertThat(achados).singleElement().satisfies(a -> {
                assertThat(a.regra()).isEqualTo("VOLUME_SEM_ANEXO");
                assertThat(a.severidade()).isEqualTo(Severidade.MEDIA);
                assertThat(a.arn()).isEqualTo("arn:aws:ec2:us-east-1:123456789012:volume/vol-0a1");
                assertThat(a.status()).isEqualTo(StatusAchado.ABERTO);
                assertThat(a.primeiraVezVisto()).isEqualTo(AGORA);
                assertThat(a.descricao()).contains("100 GiB").contains("45 dias");
            });
        }

        @Test
        void anexadoNaoEAchado() {
            assertThat(regra.avaliar(volume(null))).isEmpty();
        }

        @Test
        void semAnexoHaPoucoTempoNaoEAchado() {
            assertThat(regra.avaliar(volume(haDias(5)))).isEmpty();
        }

        @Test
        void bordaExatamenteNDiasAindaNaoEAchadoEUmSegundoAMaisE() {
            assertThat(regra.avaliar(volume(haDias(30)))).isEmpty();
            assertThat(regra.avaliar(volume(haDias(30).minusSeconds(1)))).hasSize(1);
        }

        @Test
        void naoSeAplicaAOutrosTipos() {
            var bucket = new BucketS3("arn:aws:s3:::exemplo", "us-east-1", SEM_TAGS, BloqueioDeAcessoPublico.nenhum());
            assertThat(regra.avaliar(bucket)).isEmpty();
        }
    }

    @Nested
    class BucketSemBloqueioPublico {

        final BucketSemBloqueioPublicoRegra regra = new BucketSemBloqueioPublicoRegra(RELOGIO);

        BucketS3 bucket(BloqueioDeAcessoPublico bloqueio) {
            return new BucketS3("arn:aws:s3:::bucket-sintetico", "us-east-1", SEM_TAGS, bloqueio);
        }

        @Test
        void semNenhumaProtecaoEAchadoAlto() {
            assertThat(regra.avaliar(bucket(BloqueioDeAcessoPublico.nenhum()))).singleElement().satisfies(a -> {
                assertThat(a.regra()).isEqualTo("BUCKET_SEM_BLOCK_PUBLIC_ACCESS");
                assertThat(a.severidade()).isEqualTo(Severidade.ALTA);
            });
        }

        @Test
        void comAsQuatroProtecoesNaoEAchado() {
            assertThat(regra.avaliar(bucket(BloqueioDeAcessoPublico.completo()))).isEmpty();
        }

        @Test
        void bordaFaltandoSoUmaDasQuatroProtecoesJaEAchado() {
            assertThat(regra.avaliar(bucket(new BloqueioDeAcessoPublico(true, true, true, false)))).hasSize(1);
            assertThat(regra.avaliar(bucket(new BloqueioDeAcessoPublico(false, true, true, true)))).hasSize(1);
        }
    }

    @Nested
    class ChaveIamParada {

        final ChaveIamParadaRegra regra = new ChaveIamParadaRegra(RELOGIO, 90);

        ChaveIam chave(boolean ativa, Instant criadaEm, Instant ultimoUso) {
            return new ChaveIam("arn:aws:iam::123456789012:user/usuario-sintetico", "global", SEM_TAGS, "usuario-sintetico",
                    ativa, criadaEm, ultimoUso);
        }

        @Test
        void ativaSemUsoHaMaisDe90DiasEAchado() {
            assertThat(regra.avaliar(chave(true, haDias(400), haDias(120)))).singleElement().satisfies(a -> {
                assertThat(a.regra()).isEqualTo("CHAVE_IAM_PARADA");
                assertThat(a.descricao()).contains("usuario-sintetico").contains("120 dias");
            });
        }

        @Test
        void usadaRecentementeNaoEAchado() {
            assertThat(regra.avaliar(chave(true, haDias(400), haDias(3)))).isEmpty();
        }

        @Test
        void inativaNaoEAchadoMesmoParada() {
            assertThat(regra.avaliar(chave(false, haDias(400), haDias(300)))).isEmpty();
        }

        @Test
        void nuncaUsadaContaAPartirDaCriacao() {
            assertThat(regra.avaliar(chave(true, haDias(200), null))).singleElement()
                    .satisfies(a -> assertThat(a.descricao()).contains("nunca usada").contains("200 dias"));
            assertThat(regra.avaliar(chave(true, haDias(10), null))).isEmpty();
        }

        @Test
        void bordaExatamente90DiasAindaNaoEAchado() {
            assertThat(regra.avaliar(chave(true, haDias(400), haDias(90)))).isEmpty();
            assertThat(regra.avaliar(chave(true, haDias(400), haDias(90).minusSeconds(1)))).hasSize(1);
        }
    }

    @Nested
    class SecurityGroupAberto {

        final SecurityGroupAbertoRegra regra = new SecurityGroupAbertoRegra(RELOGIO);

        SecurityGroup grupo(RegraDeEntrada... entradas) {
            return new SecurityGroup("arn:aws:ec2:us-east-1:123456789012:security-group/sg-0b2", "us-east-1", SEM_TAGS,
                    List.of(entradas));
        }

        @Test
        void sshAbertoParaAInternetEAchadoCritico() {
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 22, 22, "0.0.0.0/0")))).singleElement()
                    .satisfies(a -> {
                        assertThat(a.severidade()).isEqualTo(Severidade.CRITICA);
                        assertThat(a.descricao()).contains("SSH");
                    });
        }

        @Test
        void rdpEIPv6TambemContam() {
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 3389, 3389, "::/0")))).singleElement()
                    .satisfies(a -> assertThat(a.descricao()).contains("RDP"));
        }

        @Test
        void sshEmRedeInternaNaoEAchado() {
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 22, 22, "10.0.0.0/8")))).isEmpty();
        }

        @Test
        void portaHttpAbertaNaoEAchado() {
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 443, 443, "0.0.0.0/0")))).isEmpty();
        }

        @Test
        void intervaloQueIncluiAPortaContaEBordasDoIntervalo() {
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 0, 65535, "0.0.0.0/0")))).hasSize(2); // 22 e 3389
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 22, 80, "0.0.0.0/0")))).hasSize(1); // borda inferior
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 1, 22, "0.0.0.0/0")))).hasSize(1); // borda superior
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("tcp", 23, 3388, "0.0.0.0/0")))).isEmpty(); // logo fora
        }

        @Test
        void todosOsProtocolosAbertosCobremSshERdp() {
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("-1", 0, 0, "0.0.0.0/0")))).hasSize(2);
        }

        @Test
        void udpNaPorta22NaoEAchado() {
            assertThat(regra.avaliar(grupo(new RegraDeEntrada("udp", 22, 22, "0.0.0.0/0")))).isEmpty();
        }

        @Test
        void semEntradasNaoEAchado() {
            assertThat(regra.avaliar(grupo())).isEmpty();
        }
    }
}
