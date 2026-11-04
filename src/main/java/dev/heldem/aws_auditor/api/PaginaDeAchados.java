package dev.heldem.aws_auditor.api;

import java.util.List;

/** @param proximoCursor passe como {@code cursor} para pedir a página seguinte; {@code null} na última */
record PaginaDeAchados(List<AchadoResponse> itens, String proximoCursor) {
}
