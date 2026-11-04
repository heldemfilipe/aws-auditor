package dev.heldem.aws_auditor.api;

class ConflitoException extends RuntimeException {

    ConflitoException(String mensagem) {
        super(mensagem);
    }
}
