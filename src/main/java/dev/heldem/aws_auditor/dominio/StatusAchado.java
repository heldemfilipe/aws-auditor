package dev.heldem.aws_auditor.dominio;

/** Ciclo de vida de um achado: ABERTO enquanto o problema existe, RESOLVIDO quando some, IGNORADO por decisão humana. */
public enum StatusAchado {
    ABERTO, RESOLVIDO, IGNORADO
}
