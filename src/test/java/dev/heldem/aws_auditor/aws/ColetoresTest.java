package dev.heldem.aws_auditor.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.heldem.aws_auditor.dominio.BucketS3;
import dev.heldem.aws_auditor.dominio.BucketS3.BloqueioDeAcessoPublico;
import dev.heldem.aws_auditor.dominio.ChaveIam;
import dev.heldem.aws_auditor.dominio.SecurityGroup;
import dev.heldem.aws_auditor.dominio.SecurityGroup.RegraDeEntrada;
import dev.heldem.aws_auditor.dominio.VolumeEbs;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Ipv6Range;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeAttachment;
import software.amazon.awssdk.services.ec2.paginators.DescribeSecurityGroupsIterable;
import software.amazon.awssdk.services.ec2.paginators.DescribeVolumesIterable;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKeyLastUsed;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.GetAccessKeyLastUsedRequest;
import software.amazon.awssdk.services.iam.model.GetAccessKeyLastUsedResponse;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.ListAccessKeysResponse;
import software.amazon.awssdk.services.iam.model.ListUsersRequest;
import software.amazon.awssdk.services.iam.model.ListUsersResponse;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.iam.model.User;
import software.amazon.awssdk.services.iam.paginators.ListAccessKeysIterable;
import software.amazon.awssdk.services.iam.paginators.ListUsersIterable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListBucketsIterable;

/**
 * Os adapters (SDK -> domínio), com respostas sintéticas do SDK (conta fictícia 123456789012). Os paginadores são os REAIS
 * do SDK; só a chamada de baixo (a que iria à rede) é substituída, então a paginação por token é exercitada de verdade.
 */
class ColetoresTest {

    static final String CONTA = "123456789012";
    static final String REGIAO = "us-east-1";

    ClientesAws clientes = mock(ClientesAws.class);

    // --- EC2: volumes ---

    @Test
    void volumesSegueAPaginacaoEConverteAnexadoESolto() {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(clientes.ec2(REGIAO)).thenReturn(ec2);
        when(ec2.describeVolumesPaginator(any(DescribeVolumesRequest.class)))
                .thenAnswer(i -> new DescribeVolumesIterable(ec2, i.getArgument(0)));
        Instant criado = Instant.parse("2026-01-10T00:00:00Z");
        var anexado = Volume.builder().volumeId("vol-0a1").size(50)
                .attachments(VolumeAttachment.builder().instanceId("i-0abc").build()).createTime(criado).build();
        var solto = Volume.builder().volumeId("vol-0b2").size(200).createTime(criado)
                .tags(Tag.builder().key("dono").value("equipe-x").build()).build();
        when(ec2.describeVolumes(any(DescribeVolumesRequest.class))).thenAnswer(i -> {
            DescribeVolumesRequest pedido = i.getArgument(0);
            return pedido.nextToken() == null
                    ? DescribeVolumesResponse.builder().volumes(anexado).nextToken("pagina-2").build()
                    : DescribeVolumesResponse.builder().volumes(solto).build();
        });

        var recursos = new ColetorDeVolumes(clientes).coletar(CONTA, REGIAO);

        assertThat(recursos).hasSize(2); // as DUAS páginas
        var v1 = (VolumeEbs) recursos.get(0);
        assertThat(v1.arn()).isEqualTo("arn:aws:ec2:us-east-1:123456789012:volume/vol-0a1");
        assertThat(v1.anexado()).isTrue();
        var v2 = (VolumeEbs) recursos.get(1);
        assertThat(v2.anexado()).isFalse();
        assertThat(v2.semAnexoDesde()).isEqualTo(criado); // aproximação documentada: a criação
        assertThat(v2.tamanhoGib()).isEqualTo(200);
        assertThat(v2.tags()).containsEntry("dono", "equipe-x");
    }

    // --- EC2: security groups ---

    @Test
    void securityGroupsConvertemIpv4Ipv6ETodosOsProtocolos() {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(clientes.ec2(REGIAO)).thenReturn(ec2);
        when(ec2.describeSecurityGroupsPaginator(any(DescribeSecurityGroupsRequest.class)))
                .thenAnswer(i -> new DescribeSecurityGroupsIterable(ec2, i.getArgument(0)));
        var ssh = IpPermission.builder().ipProtocol("tcp").fromPort(22).toPort(22)
                .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build(), IpRange.builder().cidrIp("10.0.0.0/8").build())
                .ipv6Ranges(Ipv6Range.builder().cidrIpv6("::/0").build()).build();
        var tudo = IpPermission.builder().ipProtocol("-1").ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build()).build();
        when(ec2.describeSecurityGroups(any(DescribeSecurityGroupsRequest.class))).thenReturn(
                DescribeSecurityGroupsResponse.builder().securityGroups(software.amazon.awssdk.services.ec2.model.SecurityGroup
                        .builder().groupId("sg-0c3").ipPermissions(ssh, tudo).build()).build());

        var recursos = new ColetorDeSecurityGroups(clientes).coletar(CONTA, REGIAO);

        var sg = (SecurityGroup) recursos.get(0);
        assertThat(sg.arn()).isEqualTo("arn:aws:ec2:us-east-1:123456789012:security-group/sg-0c3");
        assertThat(sg.entradas()).containsExactly(new RegraDeEntrada("tcp", 22, 22, "0.0.0.0/0"),
                new RegraDeEntrada("tcp", 22, 22, "10.0.0.0/8"), new RegraDeEntrada("tcp", 22, 22, "::/0"),
                new RegraDeEntrada("-1", 0, 65535, "0.0.0.0/0")); // "-1" não traz portas: vale para todas
    }

    // --- S3 ---

    @Test
    void bucketsSemConfiguracaoDeBloqueioViramNenhumEOutrosErrosPropagam() {
        S3Client s3 = mock(S3Client.class);
        when(clientes.s3()).thenReturn(s3);
        when(s3.listBucketsPaginator(any(ListBucketsRequest.class)))
                .thenAnswer(i -> new ListBucketsIterable(s3, i.getArgument(0)));
        when(s3.listBuckets(any(ListBucketsRequest.class))).thenReturn(ListBucketsResponse.builder()
                .buckets(Bucket.builder().name("protegido").bucketRegion("us-east-1").build(),
                        Bucket.builder().name("parcial").bucketRegion("sa-east-1").build(),
                        Bucket.builder().name("sem-config").bucketRegion("us-east-1").build())
                .build());
        when(s3.getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket("protegido").build()))
                .thenReturn(bloqueio(true, true, true, true));
        when(s3.getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket("parcial").build()))
                .thenReturn(bloqueio(true, false, true, null)); // campo ausente conta como desligado
        when(s3.getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket("sem-config").build()))
                .thenThrow(erroS3("NoSuchPublicAccessBlockConfiguration"));

        var recursos = new ColetorDeBuckets(clientes).coletar(CONTA);

        assertThat(recursos).extracting(r -> ((BucketS3) r).bloqueioDeAcessoPublico()).containsExactly(
                BloqueioDeAcessoPublico.completo(), new BloqueioDeAcessoPublico(true, false, true, false),
                BloqueioDeAcessoPublico.nenhum());
        assertThat(recursos.get(1).regiao()).isEqualTo("sa-east-1");
        assertThat(recursos.get(0).arn()).isEqualTo("arn:aws:s3:::protegido");

        // qualquer OUTRO erro (como falta de permissão) NÃO é "sem proteção": a varredura falha e o erro aparece
        // (doThrow: re-treinar com when(...) chamaria o mock, que ainda lançaria o erro anterior)
        org.mockito.Mockito.doThrow(erroS3("AccessDenied")).when(s3)
                .getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket("sem-config").build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ColetorDeBuckets(clientes).coletar(CONTA))
                .isInstanceOf(S3Exception.class);
    }

    private static GetPublicAccessBlockResponse bloqueio(Boolean a, Boolean b, Boolean c, Boolean d) {
        return GetPublicAccessBlockResponse.builder().publicAccessBlockConfiguration(PublicAccessBlockConfiguration
                .builder().blockPublicAcls(a).ignorePublicAcls(b).blockPublicPolicy(c).restrictPublicBuckets(d).build())
                .build();
    }

    private static S3Exception erroS3(String codigo) {
        return (S3Exception) S3Exception.builder().message(codigo)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(codigo).build()).build();
    }

    // --- IAM ---

    @Test
    void chavesIamTrazemOUltimoUsoEOStatusDeCadaChave() {
        IamClient iam = mock(IamClient.class);
        when(clientes.iam()).thenReturn(iam);
        when(iam.listUsersPaginator(any(ListUsersRequest.class)))
                .thenAnswer(i -> new ListUsersIterable(iam, i.getArgument(0)));
        when(iam.listAccessKeysPaginator(any(ListAccessKeysRequest.class)))
                .thenAnswer(i -> new ListAccessKeysIterable(iam, i.getArgument(0)));
        when(iam.listUsers(any(ListUsersRequest.class))).thenReturn(ListUsersResponse.builder()
                .users(User.builder().userName("ana-sintetica").arn("arn:aws:iam::123456789012:user/ana-sintetica").build())
                .build());
        Instant criada = Instant.parse("2025-06-01T00:00:00Z");
        Instant usada = Instant.parse("2026-02-01T00:00:00Z");
        when(iam.listAccessKeys(any(ListAccessKeysRequest.class))).thenReturn(ListAccessKeysResponse.builder()
                .accessKeyMetadata(
                        AccessKeyMetadata.builder().accessKeyId("AKIAEXEMPLO000000001").status(StatusType.ACTIVE)
                                .createDate(criada).build(),
                        AccessKeyMetadata.builder().accessKeyId("AKIAEXEMPLO000000002").status(StatusType.INACTIVE)
                                .createDate(criada).build())
                .build());
        when(iam.getAccessKeyLastUsed(GetAccessKeyLastUsedRequest.builder().accessKeyId("AKIAEXEMPLO000000001").build()))
                .thenReturn(GetAccessKeyLastUsedResponse.builder()
                        .accessKeyLastUsed(AccessKeyLastUsed.builder().lastUsedDate(usada).build()).build());
        when(iam.getAccessKeyLastUsed(GetAccessKeyLastUsedRequest.builder().accessKeyId("AKIAEXEMPLO000000002").build()))
                .thenReturn(GetAccessKeyLastUsedResponse.builder().accessKeyLastUsed(AccessKeyLastUsed.builder().build())
                        .build()); // nunca usada: sem data

        var recursos = new ColetorDeChavesIam(clientes).coletar(CONTA);

        var ativa = (ChaveIam) recursos.get(0);
        assertThat(ativa.arn()).isEqualTo("arn:aws:iam::123456789012:user/ana-sintetica/access-key/AKIAEXEMPLO000000001");
        assertThat(ativa.ativa()).isTrue();
        assertThat(ativa.ultimoUso()).isEqualTo(usada);
        assertThat(ativa.criadaEm()).isEqualTo(criada);
        var inativa = (ChaveIam) recursos.get(1);
        assertThat(inativa.ativa()).isFalse();
        assertThat(inativa.ultimoUso()).isNull();
        assertThat(ativa.usuario()).isEqualTo("ana-sintetica");
    }
}
