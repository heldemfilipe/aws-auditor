package dev.heldem.aws_auditor.aws;

import java.util.List;

/** A varredura terminou, mas algum coletor falhou: um resultado PARCIAL não pode ser tratado como "conta limpa". */
public class VarreduraFalhouException extends RuntimeException {

    private final List<String> falhas;

    public VarreduraFalhouException(List<String> falhas, Throwable primeiraCausa) {
        super("A varredura falhou em " + falhas.size() + " coletor(es): " + String.join("; ", falhas), primeiraCausa);
        this.falhas = List.copyOf(falhas);
    }

    public List<String> falhas() {
        return falhas;
    }
}
