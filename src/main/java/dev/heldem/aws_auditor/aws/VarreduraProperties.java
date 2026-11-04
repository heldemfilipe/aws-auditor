package dev.heldem.aws_auditor.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param paralela varre as regiões e os serviços em paralelo (threads virtuais) em vez de um depois do outro
 * @param paralelismoMaximo quantas chamadas à AWS podem estar em andamento ao mesmo tempo. Threads virtuais são baratas, mas
 *     o que as limita é a AWS (throttling) e o pool de conexões HTTP do cliente: sem teto, uma conta com muitas regiões
 *     dispararia tudo de uma vez
 */
@ConfigurationProperties("auditor.varredura")
public record VarreduraProperties(boolean paralela, int paralelismoMaximo) {

    public VarreduraProperties {
        paralelismoMaximo = paralelismoMaximo <= 0 ? 16 : paralelismoMaximo;
    }
}
