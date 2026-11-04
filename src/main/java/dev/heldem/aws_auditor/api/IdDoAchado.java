package dev.heldem.aws_auditor.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * O identificador público de um achado: o par (ARN, regra), que tem "/" e ":" e não cabe em um caminho de URL, codificado em
 * base64 seguro para URL. É opaco para o cliente; qualquer valor que não decodifica é "não encontrado".
 */
record IdDoAchado(String arn, String regra) {

    private static final String SEPARADOR = "\u0000";

    String codificar() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((arn + SEPARADOR + regra).getBytes(StandardCharsets.UTF_8));
    }

    static IdDoAchado decodificar(String id) {
        try {
            String[] partes = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8).split(SEPARADOR, -1);
            if (partes.length != 2 || partes[0].isEmpty() || partes[1].isEmpty()) {
                throw new RecursoNaoEncontradoException();
            }
            return new IdDoAchado(partes[0], partes[1]);
        } catch (IllegalArgumentException e) {
            throw new RecursoNaoEncontradoException();
        }
    }
}
