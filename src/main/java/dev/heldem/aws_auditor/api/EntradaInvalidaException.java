package dev.heldem.aws_auditor.api;

class EntradaInvalidaException extends RuntimeException {

    EntradaInvalidaException(String mensagem) {
        super(mensagem);
    }
}
