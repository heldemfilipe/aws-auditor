package dev.heldem.aws_auditor.aws;

import java.util.List;

import dev.heldem.aws_auditor.dominio.Recurso;

/** Coletor de um serviço que existe por região (EC2). */
public interface ColetorRegional extends Coletor {

    List<Recurso> coletar(String contaId, String regiao);
}
