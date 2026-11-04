package dev.heldem.aws_auditor.regras;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Recurso;
import dev.heldem.aws_auditor.dominio.SecurityGroup;
import dev.heldem.aws_auditor.dominio.Severidade;

/** Security group com SSH (22) ou RDP (3389) aberto para a internet (0.0.0.0/0 ou ::/0). Um achado por porta exposta. */
@Component
public class SecurityGroupAbertoRegra implements Regra {

    public static final String ID = "SECURITY_GROUP_ABERTO";

    private static final int SSH = 22;
    private static final int RDP = 3389;

    private final Clock clock;

    public SecurityGroupAbertoRegra(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Achado> avaliar(Recurso recurso) {
        if (!(recurso instanceof SecurityGroup grupo)) {
            return List.of();
        }
        var achados = new ArrayList<Achado>();
        boolean ssh = grupo.entradas().stream().anyMatch(e -> e.abertaParaAInternet() && e.cobrePortaTcp(SSH));
        boolean rdp = grupo.entradas().stream().anyMatch(e -> e.abertaParaAInternet() && e.cobrePortaTcp(RDP));
        var agora = clock.instant();
        // O código do achado leva a porta (SECURITY_GROUP_ABERTO:22): a identidade de um achado é (arn, regra), e SSH e RDP
        // abertos no MESMO security group são dois problemas que se resolvem separadamente.
        if (ssh) {
            achados.add(Achado.aberto(ID + ":" + SSH, Severidade.CRITICA, grupo.arn(),
                    "SSH (porta 22) aberto para a internet.", agora));
        }
        if (rdp) {
            achados.add(Achado.aberto(ID + ":" + RDP, Severidade.CRITICA, grupo.arn(),
                    "RDP (porta 3389) aberto para a internet.", agora));
        }
        return achados;
    }
}
