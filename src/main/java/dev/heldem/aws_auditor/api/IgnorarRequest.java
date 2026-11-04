package dev.heldem.aws_auditor.api;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Ignorar exige dizer POR QUÊ e ATÉ QUANDO: sem justificativa e sem validade, "ignorar" vira esquecer para sempre. */
record IgnorarRequest(@NotBlank @Size(max = 500) String justificativa, @NotNull Instant expiraEm) {
}
