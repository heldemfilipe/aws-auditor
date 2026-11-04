package dev.heldem.aws_auditor.persistencia;

import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.StatusAchado;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Um achado como o DynamoDB o guarda.
 *
 * <p><b>Chaves:</b> partição = ARN do recurso, ordenação = id da regra: "todos os achados deste recurso" é uma consulta por
 * chave, e (arn, regra) identifica o achado de forma única. O índice secundário {@code status-severidade} responde "achados
 * ABERTOS e graves" sem varrer a tabela.
 *
 * <p><b>Tempos em milissegundos desde 1970 (número), e não em texto ISO:</b> o DynamoDB compara texto letra a letra, e o ISO
 * muda de tamanho conforme a fração de segundo ("...:00Z" contra "...:00.5Z" ordenam errado). As escritas condicionais
 * comparam tempos, então precisam de um tipo que ordene certo.
 *
 * <p>É um bean mutável (e não um record) porque é o que o Enhanced Client do SDK mapeia por padrão; o resto da aplicação só vê
 * o record {@code Achado}.
 */
@DynamoDbBean
public class AchadoItem {

    public static final String INDICE_STATUS_SEVERIDADE = "status-severidade";

    private String arn;
    private String regra;
    private StatusAchado status;
    private Severidade severidade;
    private int severidadeNivel;
    private String descricao;
    private long primeiraVezVisto;
    private long ultimaVezVisto;
    private Long resolvidoEm;
    private Long reabertoEm;
    private Long ignoradoAte;
    private String justificativa;

    @DynamoDbPartitionKey
    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    @DynamoDbSortKey
    public String getRegra() {
        return regra;
    }

    public void setRegra(String regra) {
        this.regra = regra;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = INDICE_STATUS_SEVERIDADE)
    public StatusAchado getStatus() {
        return status;
    }

    public void setStatus(StatusAchado status) {
        this.status = status;
    }

    public Severidade getSeveridade() {
        return severidade;
    }

    public void setSeveridade(Severidade severidade) {
        this.severidade = severidade;
    }

    /** 0 (baixa) a 3 (crítica): número, para o índice permitir "severidade maior ou igual a ALTA". */
    @DynamoDbSecondarySortKey(indexNames = INDICE_STATUS_SEVERIDADE)
    public int getSeveridadeNivel() {
        return severidadeNivel;
    }

    public void setSeveridadeNivel(int severidadeNivel) {
        this.severidadeNivel = severidadeNivel;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public long getPrimeiraVezVisto() {
        return primeiraVezVisto;
    }

    public void setPrimeiraVezVisto(long primeiraVezVisto) {
        this.primeiraVezVisto = primeiraVezVisto;
    }

    public long getUltimaVezVisto() {
        return ultimaVezVisto;
    }

    public void setUltimaVezVisto(long ultimaVezVisto) {
        this.ultimaVezVisto = ultimaVezVisto;
    }

    public Long getResolvidoEm() {
        return resolvidoEm;
    }

    public void setResolvidoEm(Long resolvidoEm) {
        this.resolvidoEm = resolvidoEm;
    }

    /** Até quando o achado fica ignorado (ms desde 1970); passada a data, ele volta a ABERTO. */
    public Long getIgnoradoAte() {
        return ignoradoAte;
    }

    public void setIgnoradoAte(Long ignoradoAte) {
        this.ignoradoAte = ignoradoAte;
    }

    /** Por que um humano mandou ignorar. */
    public String getJustificativa() {
        return justificativa;
    }

    public void setJustificativa(String justificativa) {
        this.justificativa = justificativa;
    }

    public Long getReabertoEm() {
        return reabertoEm;
    }

    public void setReabertoEm(Long reabertoEm) {
        this.reabertoEm = reabertoEm;
    }
}
