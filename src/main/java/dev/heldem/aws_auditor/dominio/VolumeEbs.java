package dev.heldem.aws_auditor.dominio;

import java.time.Instant;
import java.util.Map;

/**
 * @param semAnexoDesde desde quando o volume está sem anexo; {@code null} se está anexado a uma instância
 */
public record VolumeEbs(String arn, String regiao, Map<String, String> tags, int tamanhoGib, Instant semAnexoDesde)
        implements Recurso {

    public VolumeEbs {
        tags = Map.copyOf(tags);
    }

    public boolean anexado() {
        return semAnexoDesde == null;
    }
}
