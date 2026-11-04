package dev.heldem.aws_auditor.persistencia;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import dev.heldem.aws_auditor.aws.VarreduraDaConta;
import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.notificacao.NotificadorTelegram;

/** Uma execução completa: varre a conta, aplica as regras e registra o que mudou desde a execução anterior. */
@Service
public class AuditoriaService {

    public record Execucao(int recursosLidos, List<Achado> achados, ResultadoDoDiff diff) {
    }

    private final VarreduraDaConta varredura;
    private final RepositorioDeAchados repositorio;
    private final Clock clock;
    private final NotificadorTelegram notificador;

    public AuditoriaService(VarreduraDaConta varredura, RepositorioDeAchados repositorio, Clock clock,
            NotificadorTelegram notificador) {
        this.varredura = varredura;
        this.repositorio = repositorio;
        this.clock = clock;
        this.notificador = notificador;
    }

    public Execucao executar() {
        var resultado = varredura.executar();
        var registro = repositorio.registrarComDetalhes(resultado.achados(), clock.instant());
        notificador.notificarNovosGraves(registro.novosAchados()); // uma mensagem por execução, só dos novos graves
        return new Execucao(resultado.recursos().size(), resultado.achados(), registro.diff());
    }
}
