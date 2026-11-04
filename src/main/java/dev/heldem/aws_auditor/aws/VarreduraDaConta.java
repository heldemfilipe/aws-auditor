package dev.heldem.aws_auditor.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.motor.MotorDeRegras;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

/**
 * A varredura inteira: descobre a conta (STS), coleta os serviços globais (S3, IAM) e os regionais (EC2, uma vez por região)
 * e entrega tudo ao motor de regras. É a ÚNICA peça que junta o SDK ao motor.
 *
 * <p>Cada par (coletor, região) é uma tarefa independente. Com {@code auditor.varredura.paralela=true} as tarefas rodam em
 * threads virtuais (com um teto de chamadas simultâneas); sem isso, uma depois da outra. O resultado é o mesmo: a ordem dos
 * recursos segue a das tarefas, não a de quem terminou primeiro.
 *
 * <p>Métricas (Micrometer): {@code auditor.varredura.duracao} (tempo total, rótulo {@code paralela}),
 * {@code auditor.achados} (achados da última varredura, por severidade) e {@code auditor.varredura.erros} (por serviço).
 */
@Component
public class VarreduraDaConta {

    private static final Logger log = LoggerFactory.getLogger(VarreduraDaConta.class);

    /** @param recursos tudo que foi lido; @param achados o que as regras acharam */
    public record Resultado(List<Recurso> recursos, List<Achado> achados) {
    }

    private record Tarefa(String servico, String descricao, Callable<List<Recurso>> acao) {
    }

    private record Saida(Tarefa tarefa, List<Recurso> recursos, Throwable erro) {
    }

    private final ClientesAws clientes;
    private final AwsProperties propriedades;
    private final VarreduraProperties varredura;
    private final List<ColetorGlobal> globais;
    private final List<ColetorRegional> regionais;
    private final MotorDeRegras motor;
    private final MeterRegistry metricas;
    private final AtomicInteger[] achadosPorSeveridade = new AtomicInteger[Severidade.values().length];

    public VarreduraDaConta(ClientesAws clientes, AwsProperties propriedades, VarreduraProperties varredura,
            List<ColetorGlobal> globais, List<ColetorRegional> regionais, MotorDeRegras motor, MeterRegistry metricas) {
        this.clientes = clientes;
        this.propriedades = propriedades;
        this.varredura = varredura;
        this.globais = globais;
        this.regionais = regionais;
        this.motor = motor;
        this.metricas = metricas;
        for (Severidade severidade : Severidade.values()) {
            var valor = new AtomicInteger();
            achadosPorSeveridade[severidade.ordinal()] = valor;
            Gauge.builder("auditor.achados", valor, AtomicInteger::get).description("Achados da última varredura")
                    .tag("severidade", severidade.name()).register(metricas);
        }
    }

    public Resultado executar() {
        var cronometro = Timer.start(metricas);
        try {
            String conta = descobrirConta();
            List<Saida> saidas = varredura.paralela() ? emParalelo(tarefas(conta)) : emSequencia(tarefas(conta));

            var falhas = new ArrayList<String>();
            Throwable primeira = null;
            for (Saida saida : saidas) {
                if (saida.erro() != null) {
                    metricas.counter("auditor.varredura.erros", Tags.of("servico", saida.tarefa().servico())).increment();
                    falhas.add(saida.tarefa().descricao() + ": " + saida.erro());
                    primeira = primeira == null ? saida.erro() : primeira;
                    log.error("Falha em {}", saida.tarefa().descricao(), saida.erro());
                }
            }
            if (!falhas.isEmpty()) {
                throw new VarreduraFalhouException(falhas, primeira);
            }

            var recursos = new ArrayList<Recurso>();
            saidas.forEach(saida -> recursos.addAll(saida.recursos()));
            var achados = motor.auditar(recursos);
            for (Severidade severidade : Severidade.values()) {
                achadosPorSeveridade[severidade.ordinal()]
                        .set((int) achados.stream().filter(a -> a.severidade() == severidade).count());
            }
            return new Resultado(List.copyOf(recursos), achados);
        } finally {
            cronometro.stop(Timer.builder("auditor.varredura.duracao").description("Duração da varredura inteira")
                    .tag("paralela", String.valueOf(varredura.paralela())).register(metricas));
        }
    }

    private String descobrirConta() {
        try {
            // GetCallerIdentity não exige permissão IAM nenhuma
            return clientes.sts().getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
        } catch (RuntimeException e) {
            metricas.counter("auditor.varredura.erros", Tags.of("servico", "sts")).increment();
            throw e;
        }
    }

    private List<Tarefa> tarefas(String conta) {
        var tarefas = new ArrayList<Tarefa>();
        for (ColetorGlobal coletor : globais) {
            tarefas.add(new Tarefa(coletor.servico(), coletor.getClass().getSimpleName(), () -> coletor.coletar(conta)));
        }
        for (String regiao : propriedades.regioes()) {
            for (ColetorRegional coletor : regionais) {
                tarefas.add(new Tarefa(coletor.servico(), coletor.getClass().getSimpleName() + " em " + regiao,
                        () -> coletor.coletar(conta, regiao)));
            }
        }
        return tarefas;
    }

    private static Saida executar(Tarefa tarefa) {
        try {
            return new Saida(tarefa, tarefa.acao().call(), null);
        } catch (Exception | Error e) {
            return new Saida(tarefa, List.of(), e);
        }
    }

    private List<Saida> emSequencia(List<Tarefa> tarefas) {
        return tarefas.stream().map(VarreduraDaConta::executar).toList();
    }

    /** Uma thread virtual por tarefa; o semáforo limita quantas chamam a AWS ao mesmo tempo. A ordem do resultado é a das tarefas. */
    private List<Saida> emParalelo(List<Tarefa> tarefas) {
        var teto = new Semaphore(varredura.paralelismoMaximo());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futuros = new ArrayList<Future<Saida>>();
            for (Tarefa tarefa : tarefas) {
                futuros.add(executor.submit(() -> {
                    teto.acquire();
                    try {
                        return executar(tarefa);
                    } finally {
                        teto.release();
                    }
                }));
            }
            var saidas = new ArrayList<Saida>();
            for (Future<Saida> futuro : futuros) {
                try {
                    saidas.add(futuro.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Varredura interrompida", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            return saidas;
        }
    }
}
