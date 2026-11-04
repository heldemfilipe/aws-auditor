package dev.heldem.aws_auditor.motor;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.regras.Regra;

/**
 * Aplica todas as regras a todos os recursos. Não conhece a AWS (só o domínio) nem nenhuma regra em particular: as regras
 * chegam pelo Spring, então acrescentar uma é criar um {@code @Component} que implementa {@link Regra}.
 */
@Component
public class MotorDeRegras {

    private final List<Regra> regras;

    public MotorDeRegras(List<Regra> regras) {
        this.regras = List.copyOf(regras);
    }

    public List<String> idsDasRegras() {
        return regras.stream().map(Regra::id).sorted().toList();
    }

    /** Os achados de todos os recursos, do mais grave para o menos grave (desempate por ARN e regra, para ser estável). */
    public List<Achado> auditar(Collection<? extends Recurso> recursos) {
        return recursos.stream()
                .flatMap(recurso -> regras.stream().flatMap(regra -> regra.avaliar(recurso).stream()))
                .sorted(Comparator.comparing(Achado::severidade).reversed().thenComparing(Achado::arn)
                        .thenComparing(Achado::regra).thenComparing(Achado::descricao))
                .toList();
    }
}
