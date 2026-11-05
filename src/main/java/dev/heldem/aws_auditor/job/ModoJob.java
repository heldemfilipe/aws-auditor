package dev.heldem.aws_auditor.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.persistencia.AuditoriaService;
import dev.heldem.aws_auditor.persistencia.RepositorioDeAchados;

/**
 * Modo job: executa UMA auditoria e encerra o processo com um código de saída, para o agendador (o ECS, disparado pelo
 * EventBridge) saber se deu certo. Liga com {@code AUDITOR_JOB_HABILITADO=true}; sem isso a aplicação sobe como API normal.
 *
 * <p>Código de saída: <b>0</b> = a auditoria rodou e foi registrada (mesmo que ache problemas: achar problema é o
 * trabalho, não uma falha do job); <b>1</b> = falhou (sem credenciais, sem permissão, DynamoDB fora, coletor com erro...).
 * Em produção, rode também com {@code SPRING_MAIN_WEB_APPLICATION_TYPE=none}: o job não precisa de servidor web.
 */
@Component
@ConditionalOnProperty(name = "auditor.job.habilitado", havingValue = "true")
public class ModoJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModoJob.class);

    private final AuditoriaService auditoria;
    private final RepositorioDeAchados repositorio;
    private final ConfigurableApplicationContext contexto;
    private final boolean criarTabela;

    public ModoJob(AuditoriaService auditoria, RepositorioDeAchados repositorio, ConfigurableApplicationContext contexto,
            @Value("${auditor.dynamodb.criar-tabela:false}") boolean criarTabela) {
        this.auditoria = auditoria;
        this.repositorio = repositorio;
        this.contexto = contexto;
        this.criarTabela = criarTabela;
    }

    /** @return o código de saída do processo (0 ou 1) */
    public int executar() {
        try {
            if (criarTabela) { // só para rodar local; em produção a tabela é do Terraform
                repositorio.criarTabelaSeNaoExistir();
            }
            var execucao = auditoria.executar();
            var diff = execucao.diff();
            log.info("Auditoria concluída: {} recursos lidos, {} achados ({} novos, {} persistentes, {} resolvidos, {} reabertos).",
                    execucao.recursosLidos(), execucao.achados().size(), diff.novos(), diff.persistentes(), diff.resolvidos(),
                    diff.reabertos());
            return 0;
        } catch (Exception | Error e) {
            log.error("Auditoria falhou: {}", e.toString(), e);
            return 1;
        }
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        int codigo = executar();
        System.exit(SpringApplication.exit(contexto, () -> codigo));
    }
}
