package dev.heldem.aws_auditor.aws;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.VolumeEbs;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;

/**
 * Volumes EBS. Usa o paginador do SDK ({@code describeVolumesPaginator}), que segue o {@code NextToken} sozinho.
 *
 * <p><b>Aproximação:</b> a API {@code DescribeVolumes} NÃO informa quando um volume foi desanexado. Um volume sem anexo
 * entra com {@code semAnexoDesde = createTime} (a criação), que é um LIMITE SUPERIOR do tempo parado: um volume antigo que
 * foi desanexado ontem aparece como "sem anexo há meses". A Etapa 3 melhora isso com o "primeira vez visto" do achado.
 */
@Component
public class ColetorDeVolumes implements ColetorRegional {

    private final ClientesAws clientes;

    public ColetorDeVolumes(ClientesAws clientes) {
        this.clientes = clientes;
    }

    @Override
    public String servico() {
        return "ec2";
    }

    @Override
    public List<String> acoesIam() {
        return List.of("ec2:DescribeVolumes");
    }

    @Override
    public List<Recurso> coletar(String contaId, String regiao) {
        var ec2 = clientes.ec2(regiao);
        return ec2.describeVolumesPaginator(DescribeVolumesRequest.builder().build()).volumes().stream()
                .<Recurso>map(volume -> paraDominio(volume, contaId, regiao)).toList();
    }

    static VolumeEbs paraDominio(Volume volume, String contaId, String regiao) {
        boolean anexado = !volume.attachments().isEmpty();
        return new VolumeEbs("arn:aws:ec2:%s:%s:volume/%s".formatted(regiao, contaId, volume.volumeId()), regiao,
                tags(volume.tags()), volume.size() == null ? 0 : volume.size(), anexado ? null : volume.createTime());
    }

    static Map<String, String> tags(List<Tag> tags) {
        return tags.stream().collect(Collectors.toMap(Tag::key, t -> t.value() == null ? "" : t.value(), (a, b) -> b));
    }
}
