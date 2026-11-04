package dev.heldem.aws_auditor.dominio;

/** Quão grave é um achado. A ordem do enum é da menos para a mais grave (compareTo e {@link #ehAltaOuMais()} dependem dela). */
public enum Severidade {
    BAIXA, MEDIA, ALTA, CRITICA;

    public boolean ehAltaOuMais() {
        return compareTo(ALTA) >= 0;
    }
}
