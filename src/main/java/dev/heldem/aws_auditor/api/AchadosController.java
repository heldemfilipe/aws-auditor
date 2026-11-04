package dev.heldem.aws_auditor.api;

import java.time.Clock;
import java.time.Duration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.StatusAchado;
import dev.heldem.aws_auditor.persistencia.RepositorioDeAchados;
import dev.heldem.aws_auditor.persistencia.RepositorioDeAchados.ResultadoDoIgnorar;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/achados")
class AchadosController {

    private static final int LIMITE_PADRAO = 20;
    private static final int LIMITE_MAXIMO = 100;
    private static final Duration VALIDADE_MAXIMA = Duration.ofDays(365);

    private final RepositorioDeAchados repositorio;
    private final Clock clock;

    AchadosController(RepositorioDeAchados repositorio, Clock clock) {
        this.repositorio = repositorio;
        this.clock = clock;
    }

    /**
     * Achados de um status (padrão ABERTO), do mais grave para o menos grave, paginados (cursor), pelo índice secundário.
     * {@code severidadeMinima} filtra no próprio índice (ALTA devolve ALTA e CRITICA).
     */
    @GetMapping
    PaginaDeAchados listar(@RequestParam(defaultValue = "ABERTO") StatusAchado status,
            @RequestParam(required = false) Severidade severidadeMinima,
            @RequestParam(defaultValue = "" + LIMITE_PADRAO) int limite, @RequestParam(required = false) String cursor) {
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new EntradaInvalidaException("limite deve estar entre 1 e " + LIMITE_MAXIMO + ".");
        }
        repositorio.reabrirIgnoradosExpirados(clock.instant()); // passada a data, o achado ignorado volta a aparecer
        var pagina = repositorio.listar(status, severidadeMinima, limite, cursor);
        return new PaginaDeAchados(pagina.itens().stream().map(AchadoResponse::de).toList(), pagina.proximoCursor());
    }

    @GetMapping("/{id}")
    AchadoResponse buscar(@PathVariable String id) {
        var chave = IdDoAchado.decodificar(id);
        repositorio.reabrirIgnoradosExpirados(clock.instant());
        return repositorio.buscar(chave.arn(), chave.regra()).map(AchadoResponse::de)
                .orElseThrow(RecursoNaoEncontradoException::new);
    }

    @PostMapping("/{id}/ignorar")
    AchadoResponse ignorar(@PathVariable String id, @Valid @RequestBody IgnorarRequest pedido) {
        var chave = IdDoAchado.decodificar(id);
        var agora = clock.instant();
        if (!pedido.expiraEm().isAfter(agora)) {
            throw new EntradaInvalidaException("expiraEm deve estar no futuro.");
        }
        if (pedido.expiraEm().isAfter(agora.plus(VALIDADE_MAXIMA))) {
            throw new EntradaInvalidaException("expiraEm não pode passar de 365 dias: ignorar não é esquecer para sempre.");
        }
        ResultadoDoIgnorar resultado = repositorio.ignorar(chave.arn(), chave.regra(), pedido.justificativa().strip(),
                pedido.expiraEm());
        switch (resultado) {
            case NAO_ENCONTRADO -> throw new RecursoNaoEncontradoException();
            case NAO_APLICAVEL -> throw new ConflitoException("Só é possível ignorar um achado ABERTO ou já IGNORADO; este foi resolvido.");
            case IGNORADO -> {
                // segue
            }
        }
        return repositorio.buscar(chave.arn(), chave.regra()).map(AchadoResponse::de)
                .orElseThrow(RecursoNaoEncontradoException::new);
    }
}
