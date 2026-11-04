package dev.heldem.aws_auditor.aws;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.motor.MotorDeRegras;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

/**
 * A varredura inteira: descobre a conta (STS), coleta os serviços globais uma vez e os regionais em cada região, e entrega os
 * recursos ao motor de regras. É a ÚNICA peça que junta o SDK ao motor.
 */
@Component
public class VarreduraDaConta {

    /** @param recursos tudo que foi lido; @param achados o que as regras acharam */
    public record Resultado(List<Recurso> recursos, List<Achado> achados) {
    }

    private final ClientesAws clientes;
    private final AwsProperties propriedades;
    private final List<ColetorGlobal> globais;
    private final List<ColetorRegional> regionais;
    private final MotorDeRegras motor;

    public VarreduraDaConta(ClientesAws clientes, AwsProperties propriedades, List<ColetorGlobal> globais,
            List<ColetorRegional> regionais, MotorDeRegras motor) {
        this.clientes = clientes;
        this.propriedades = propriedades;
        this.globais = globais;
        this.regionais = regionais;
        this.motor = motor;
    }

    public Resultado executar() {
        // GetCallerIdentity não exige permissão IAM nenhuma
        String conta = clientes.sts().getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
        var recursos = new ArrayList<Recurso>();
        globais.forEach(coletor -> recursos.addAll(coletor.coletar(conta)));
        for (String regiao : propriedades.regioes()) {
            regionais.forEach(coletor -> recursos.addAll(coletor.coletar(conta, regiao)));
        }
        return new Resultado(List.copyOf(recursos), motor.auditar(recursos));
    }
}
