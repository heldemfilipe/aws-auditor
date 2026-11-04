package dev.heldem.aws_auditor.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    ProblemDetail naoEncontrado() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Achado não encontrado.");
    }

    @ExceptionHandler(ConflitoException.class)
    ProblemDetail conflito(ConflitoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({ EntradaInvalidaException.class, IllegalArgumentException.class })
    ProblemDetail entradaInvalida(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
