package dev.heldem.aws_auditor.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import dev.heldem.aws_auditor.aws.VarreduraFalhouException;
import dev.heldem.aws_auditor.persistencia.AuditoriaService;
import dev.heldem.aws_auditor.persistencia.AuditoriaService.Execucao;
import dev.heldem.aws_auditor.persistencia.RepositorioDeAchados;
import dev.heldem.aws_auditor.persistencia.ResultadoDoDiff;

/** O código de saída do job: 0 quando a auditoria rodou (mesmo achando problemas), 1 quando falhou. */
class ModoJobTest {

    AuditoriaService auditoria = mock(AuditoriaService.class);
    RepositorioDeAchados repositorio = mock(RepositorioDeAchados.class);
    ConfigurableApplicationContext contexto = mock(ConfigurableApplicationContext.class);

    ModoJob job(boolean criarTabela) {
        return new ModoJob(auditoria, repositorio, contexto, criarTabela);
    }

    @Test
    void auditoriaQueRodaSaiComZeroMesmoComAchados() {
        when(auditoria.executar()).thenReturn(new Execucao(10, List.of(), new ResultadoDoDiff(2, 1, 0, 0, 0)));

        assertThat(job(false).executar()).isZero();
        verify(repositorio, never()).criarTabelaSeNaoExistir(); // em produção a tabela é do Terraform
    }

    @Test
    void falhaDaAuditoriaSaiComUm() {
        when(auditoria.executar()).thenThrow(new VarreduraFalhouException(List.of("ColetorDeVolumes em us-east-1: sem permissão"), null));

        assertThat(job(false).executar()).isEqualTo(1);
    }

    @Test
    void qualquerErroInclusiveDeInfraestruturaSaiComUm() {
        when(auditoria.executar()).thenThrow(new IllegalStateException("DynamoDB fora"));

        assertThat(job(false).executar()).isEqualTo(1);
    }

    @Test
    void localPodeCriarATabelaAntesDeRodar() {
        when(auditoria.executar()).thenReturn(new Execucao(0, List.of(), new ResultadoDoDiff(0, 0, 0, 0, 0)));

        assertThat(job(true).executar()).isZero();
        verify(repositorio).criarTabelaSeNaoExistir();
    }
}
