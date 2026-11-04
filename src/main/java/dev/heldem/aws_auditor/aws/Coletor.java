package dev.heldem.aws_auditor.aws;

import java.util.List;

/**
 * Lê um serviço da AWS e devolve recursos do DOMÍNIO (o motor de regras nunca vê um tipo do SDK). Cada coletor declara as
 * ações IAM que usa: um teste confere que a policy mínima do repositório tem exatamente essas ações.
 */
public interface Coletor {

    /** As ações IAM chamadas por este coletor (ex.: "ec2:DescribeVolumes"). */
    List<String> acoesIam();
}
