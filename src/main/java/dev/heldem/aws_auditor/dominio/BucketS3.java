package dev.heldem.aws_auditor.dominio;

import java.util.Map;

/**
 * @param bloqueioDeAcessoPublico as quatro proteções do "block public access" do bucket; um bucket SEM essa configuração
 *     é representado por {@link BloqueioDeAcessoPublico#nenhum()}, e não por {@code null}
 */
public record BucketS3(String arn, String regiao, Map<String, String> tags, BloqueioDeAcessoPublico bloqueioDeAcessoPublico)
        implements Recurso {

    public BucketS3 {
        tags = Map.copyOf(tags);
    }

    public record BloqueioDeAcessoPublico(boolean bloqueiaAclsPublicas, boolean ignoraAclsPublicas,
            boolean bloqueiaPoliticasPublicas, boolean restringeBucketsPublicos) {

        public static BloqueioDeAcessoPublico nenhum() {
            return new BloqueioDeAcessoPublico(false, false, false, false);
        }

        public static BloqueioDeAcessoPublico completo() {
            return new BloqueioDeAcessoPublico(true, true, true, true);
        }

        public boolean estaCompleto() {
            return bloqueiaAclsPublicas && ignoraAclsPublicas && bloqueiaPoliticasPublicas && restringeBucketsPublicos;
        }
    }
}
