package dev.heldem.aws_auditor.dominio;

import java.util.List;
import java.util.Map;

public record SecurityGroup(String arn, String regiao, Map<String, String> tags, List<RegraDeEntrada> entradas)
        implements Recurso {

    public SecurityGroup {
        tags = Map.copyOf(tags);
        entradas = List.copyOf(entradas);
    }

    /**
     * Uma regra de entrada (ingress).
     *
     * @param protocolo "tcp", "udp", "icmp" ou "-1" (todos os protocolos, como a AWS representa)
     * @param portaInicial primeira porta do intervalo (ignorada se o protocolo é "-1")
     * @param portaFinal última porta do intervalo
     * @param origem um CIDR, como "0.0.0.0/0" ou "::/0" (a internet inteira) ou "10.0.0.0/8"
     */
    public record RegraDeEntrada(String protocolo, int portaInicial, int portaFinal, String origem) {

        public boolean abertaParaAInternet() {
            return "0.0.0.0/0".equals(origem) || "::/0".equals(origem);
        }

        /** O intervalo inclui a porta? Protocolo "-1" (todos) inclui qualquer porta; só TCP vale para SSH e RDP. */
        public boolean cobrePortaTcp(int porta) {
            if ("-1".equals(protocolo)) {
                return true;
            }
            return "tcp".equalsIgnoreCase(protocolo) && portaInicial <= porta && porta <= portaFinal;
        }
    }
}
