package dev.heldem.aws_auditor.aws;

import java.util.List;

/**
 * Lê um serviço da AWS e devolve recursos do DOMÍNIO (o motor de regras nunca vê um tipo do SDK). Cada coletor declara as
 * ações IAM que usa: um teste confere que a policy mínima do repositório tem exatamente essas ações.
 */
public interface Coletor {

    /** O serviço da AWS que este coletor lê ("ec2", "s3", "iam"): é o rótulo da métrica de erros por serviço. */
    String servico();

    /** As ações IAM chamadas por este coletor (ex.: "ec2:DescribeVolumes"). */
    List<String> acoesIam();
}
