package dev.heldem.aws_auditor.persistencia;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import dev.heldem.aws_auditor.aws.VarreduraDaConta;
import dev.heldem.aws_auditor.dominio.Achado;

/** Uma execução completa: varre a conta, aplica as regras e registra o que mudou desde a execução anterior. */
@Service
public class AuditoriaService {

    public record Execucao(int recursosLidos, List<Achado> achados, ResultadoDoDiff diff) {
    }

    private final VarreduraDaConta varredura;
    private final RepositorioDeAchados repositorio;
    private final Clock clock;

    public AuditoriaService(VarreduraDaConta varredura, RepositorioDeAchados repositorio, Clock clock) {
        this.varredura = varredura;
        this.repositorio = repositorio;
        this.clock = clock;
    }

    public Execucao executar() {
        var resultado = varredura.executar();
        var diff = repositorio.registrar(resultado.achados(), clock.instant());
        return new Execucao(resultado.recursos().size(), resultado.achados(), diff);
    }
}
