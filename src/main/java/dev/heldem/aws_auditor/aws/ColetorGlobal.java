package dev.heldem.aws_auditor.aws;

import java.util.List;

import dev.heldem.aws_auditor.dominio.Recurso;

/** Coletor de um serviço global ou cujo escopo é a conta inteira (IAM, S3). */
public interface ColetorGlobal extends Coletor {

    List<Recurso> coletar(String contaId);
}
