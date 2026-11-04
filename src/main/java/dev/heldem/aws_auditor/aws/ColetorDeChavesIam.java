package dev.heldem.aws_auditor.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.ChaveIam;
import dev.heldem.aws_auditor.dominio.Recurso;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.GetAccessKeyLastUsedRequest;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.ListUsersRequest;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.iam.model.User;

/**
 * Chaves de acesso IAM de todos os usuários, com o último uso de cada uma. Chave de acesso não tem ARN na AWS: o identificador
 * é SINTÉTICO, {@code <arn do usuário>/access-key/<id da chave>}.
 */
@Component
public class ColetorDeChavesIam implements ColetorGlobal {

    private final ClientesAws clientes;

    public ColetorDeChavesIam(ClientesAws clientes) {
        this.clientes = clientes;
    }

    @Override
    public String servico() {
        return "iam";
    }

    @Override
    public List<String> acoesIam() {
        return List.of("iam:ListUsers", "iam:ListAccessKeys", "iam:GetAccessKeyLastUsed");
    }

    @Override
    public List<Recurso> coletar(String contaId) {
        var iam = clientes.iam();
        var chaves = new ArrayList<Recurso>();
        for (User usuario : iam.listUsersPaginator(ListUsersRequest.builder().build()).users()) {
            var requisicao = ListAccessKeysRequest.builder().userName(usuario.userName()).build();
            for (AccessKeyMetadata chave : iam.listAccessKeysPaginator(requisicao).accessKeyMetadata()) {
                var ultimoUso = iam.getAccessKeyLastUsed(
                        GetAccessKeyLastUsedRequest.builder().accessKeyId(chave.accessKeyId()).build())
                        .accessKeyLastUsed().lastUsedDate();
                chaves.add(new ChaveIam(usuario.arn() + "/access-key/" + chave.accessKeyId(), "global", Map.of(),
                        usuario.userName(), chave.status() == StatusType.ACTIVE, chave.createDate(), ultimoUso));
            }
        }
        return chaves;
    }
}
