package dev.heldem.aws_auditor.aws;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.SecurityGroup;
import dev.heldem.aws_auditor.dominio.SecurityGroup.RegraDeEntrada;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.IpPermission;

/** Security groups, com as regras de ENTRADA convertidas: uma {@link RegraDeEntrada} por faixa de origem (IPv4 e IPv6). */
@Component
public class ColetorDeSecurityGroups implements ColetorRegional {

    private final ClientesAws clientes;

    public ColetorDeSecurityGroups(ClientesAws clientes) {
        this.clientes = clientes;
    }

    @Override
    public String servico() {
        return "ec2";
    }

    @Override
    public List<String> acoesIam() {
        return List.of("ec2:DescribeSecurityGroups");
    }

    @Override
    public List<Recurso> coletar(String contaId, String regiao) {
        var ec2 = clientes.ec2(regiao);
        return ec2.describeSecurityGroupsPaginator(DescribeSecurityGroupsRequest.builder().build()).securityGroups()
                .stream().<Recurso>map(grupo -> new SecurityGroup(
                        "arn:aws:ec2:%s:%s:security-group/%s".formatted(regiao, contaId, grupo.groupId()), regiao,
                        ColetorDeVolumes.tags(grupo.tags()), entradas(grupo.ipPermissions())))
                .toList();
    }

    static List<RegraDeEntrada> entradas(List<IpPermission> permissoes) {
        var entradas = new ArrayList<RegraDeEntrada>();
        for (IpPermission permissao : permissoes) {
            // protocolo "-1" não traz portas (FromPort/ToPort ausentes): vale para todas
            int de = permissao.fromPort() == null ? 0 : permissao.fromPort();
            int ate = permissao.toPort() == null ? 65535 : permissao.toPort();
            permissao.ipRanges().forEach(r -> entradas.add(new RegraDeEntrada(permissao.ipProtocol(), de, ate, r.cidrIp())));
            permissao.ipv6Ranges()
                    .forEach(r -> entradas.add(new RegraDeEntrada(permissao.ipProtocol(), de, ate, r.cidrIpv6())));
        }
        return entradas;
    }
}
