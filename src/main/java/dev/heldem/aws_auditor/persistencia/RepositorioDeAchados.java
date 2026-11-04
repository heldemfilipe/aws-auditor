package dev.heldem.aws_auditor.persistencia;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import dev.heldem.aws_auditor.dominio.Achado;
import dev.heldem.aws_auditor.dominio.Severidade;
import dev.heldem.aws_auditor.dominio.StatusAchado;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.EnhancedGlobalSecondaryIndex;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Os achados no DynamoDB e a comparação entre execuções.
 *
 * <p>Toda escrita é CONDICIONAL (ConditionExpression), então duas execuções simultâneas, ou a mesma duas vezes seguidas, não
 * duplicam nem desfazem o trabalho uma da outra:
 * <ul>
 *   <li><b>novo:</b> {@code PutItem} com {@code attribute_not_exists(arn)}: só um dos concorrentes cria;</li>
 *   <li><b>persistente:</b> atualiza o "visto por último" só se o achado está ABERTO e a data é mais nova;</li>
 *   <li><b>resolvido:</b> ABERTO vira RESOLVIDO (com a data) só se NINGUÉM o viu depois deste instante, para uma execução
 *       atrasada não resolver o que outra mais nova acabou de ver;</li>
 *   <li><b>reaberto:</b> RESOLVIDO vira ABERTO só se ainda está RESOLVIDO.</li>
 * </ul>
 * Achado resolvido nunca é apagado: fica com a data de resolução, e o histórico se mantém.
 */
@Repository
public class RepositorioDeAchados {

    private final DynamoDbTable<AchadoItem> tabela;
    private final DynamoDbClient dynamo;

    public RepositorioDeAchados(DynamoDbTable<AchadoItem> tabela, DynamoDbClient dynamo,
            @Value("${auditor.dynamodb.tabela:achados}") String nomeDaTabela) {
        this.tabela = tabela;
        this.dynamo = dynamo;
    }

    /** Cria a tabela e o índice (cobrança por requisição). Em produção quem cria é o Terraform (Etapa 6); isto é para o local. */
    public void criarTabelaSeNaoExistir() {
        try {
            tabela.createTable(construtor -> construtor
                    .globalSecondaryIndices(EnhancedGlobalSecondaryIndex.builder()
                            .indexName(AchadoItem.INDICE_STATUS_SEVERIDADE)
                            .projection(p -> p.projectionType(ProjectionType.ALL)).build()));
        } catch (ResourceInUseException jaExiste) {
            // tudo certo: a tabela já está lá
        }
    }

    public List<AchadoItem> todos() {
        return tabela.scan().items().stream().toList();
    }

    /** Os achados de um status, mais graves primeiro, pelo índice secundário (sem varrer a tabela). */
    public List<Achado> porStatus(StatusAchado status) {
        return tabela.index(AchadoItem.INDICE_STATUS_SEVERIDADE)
                .query(r -> r.queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(status.name()).build()))
                        .scanIndexForward(false))
                .stream().flatMap(pagina -> pagina.items().stream()).map(RepositorioDeAchados::paraDominio).toList();
    }

    /** ABERTOS com severidade ALTA ou mais: uma consulta no índice (status = ABERTO, severidadeNivel >= ALTA). */
    public List<Achado> abertosGraves() {
        var condicao = QueryConditional.sortGreaterThanOrEqualTo(Key.builder()
                .partitionValue(StatusAchado.ABERTO.name()).sortValue(Severidade.ALTA.ordinal()).build());
        return tabela.index(AchadoItem.INDICE_STATUS_SEVERIDADE)
                .query(r -> r.queryConditional(condicao).scanIndexForward(false)).stream()
                .flatMap(pagina -> pagina.items().stream()).map(RepositorioDeAchados::paraDominio).toList();
    }

    /**
     * Registra o resultado de UMA execução e devolve o que mudou. {@code agora} é o instante da execução (injetado, para os
     * testes controlarem o tempo).
     */
    public ResultadoDoDiff registrar(Collection<Achado> encontrados, Instant agora) {
        return registrarComDetalhes(encontrados, agora).diff();
    }

    /** O diff e os achados que foram criados AGORA (para avisar só dos novos). */
    public Registro registrarComDetalhes(Collection<Achado> encontrados, Instant agora) {
        reabrirIgnoradosExpirados(agora);
        var novosAchados = new ArrayList<Achado>();
        long ms = agora.toEpochMilli();
        Map<String, AchadoItem> existentes = new HashMap<>();
        todos().forEach(i -> existentes.put(chave(i.getArn(), i.getRegra()), i));

        int novos = 0, persistentes = 0, resolvidos = 0, reabertos = 0, ignorados = 0;
        Set<String> vistos = new HashSet<>();
        for (Achado achado : encontrados) {
            String k = chave(achado.arn(), achado.regra());
            if (!vistos.add(k)) {
                continue; // o mesmo achado duas vezes na mesma execução conta uma vez
            }
            AchadoItem existente = existentes.get(k);
            if (existente == null) {
                if (criar(achado, ms)) {
                    novos++;
                    novosAchados.add(achado);
                } else {
                    persistentes++; // outra execução o criou no meio tempo
                }
            } else if (existente.getStatus() == StatusAchado.ABERTO) {
                atualizarVisto(achado, ms, StatusAchado.ABERTO);
                persistentes++;
            } else if (existente.getStatus() == StatusAchado.RESOLVIDO) {
                if (reabrir(achado, ms)) {
                    reabertos++;
                } else {
                    persistentes++; // outra execução o reabriu primeiro
                }
            } else {
                atualizarVisto(achado, ms, StatusAchado.IGNORADO);
                ignorados++;
            }
        }
        for (AchadoItem existente : existentes.values()) {
            if (existente.getStatus() == StatusAchado.ABERTO
                    && !vistos.contains(chave(existente.getArn(), existente.getRegra())) && resolver(existente, ms)) {
                resolvidos++;
            }
        }
        return new Registro(new ResultadoDoDiff(novos, persistentes, resolvidos, reabertos, ignorados),
                List.copyOf(novosAchados));
    }

    /** @param novosAchados os achados que esta execução criou (nunca vistos antes) */
    public record Registro(ResultadoDoDiff diff, List<Achado> novosAchados) {
    }

    /** Uma página de achados e o cursor opaco da próxima ({@code null} na última). */
    public record PaginaDeItens(List<AchadoItem> itens, String proximoCursor) {
    }

    public enum ResultadoDoIgnorar {
        IGNORADO, NAO_ENCONTRADO, NAO_APLICAVEL
    }

    public Optional<AchadoItem> buscar(String arn, String regra) {
        return Optional.ofNullable(tabela.getItem(Key.builder().partitionValue(arn).sortValue(regra).build()));
    }

    /**
     * Ignora um achado ABERTO (ou prorroga um já ignorado) até a data. Um achado RESOLVIDO não tem o que ignorar.
     * Condicional: se alguém o resolveu no meio tempo, não o transforma em IGNORADO.
     */
    public ResultadoDoIgnorar ignorar(String arn, String regra, String justificativa, Instant ate) {
        boolean ok = atualizar(arn, regra, "SET #s = :ignorado, ignoradoAte = :ate, justificativa = :justificativa",
                "#s IN (:aberto, :ignorado)",
                Map.of(":ignorado", s(StatusAchado.IGNORADO.name()), ":aberto", s(StatusAchado.ABERTO.name()),
                        ":ate", n(ate.toEpochMilli()), ":justificativa", s(justificativa)));
        if (ok) {
            return ResultadoDoIgnorar.IGNORADO;
        }
        return buscar(arn, regra).isPresent() ? ResultadoDoIgnorar.NAO_APLICAVEL : ResultadoDoIgnorar.NAO_ENCONTRADO;
    }

    /** Achados ignorados cuja data passou voltam a ABERTO (e a aparecer). Condicional: só se ainda IGNORADO e vencido. */
    public int reabrirIgnoradosExpirados(Instant agora) {
        long ms = agora.toEpochMilli();
        int reabertos = 0;
        for (AchadoItem item : porStatusItens(StatusAchado.IGNORADO)) {
            if (item.getIgnoradoAte() != null && item.getIgnoradoAte() < ms
                    && atualizar(item.getArn(), item.getRegra(),
                            "SET #s = :aberto, reabertoEm = :agora REMOVE ignoradoAte, justificativa",
                            "#s = :ignorado AND ignoradoAte < :agora",
                            Map.of(":aberto", s(StatusAchado.ABERTO.name()), ":ignorado", s(StatusAchado.IGNORADO.name()),
                                    ":agora", n(ms)))) {
                reabertos++;
            }
        }
        return reabertos;
    }

    private List<AchadoItem> porStatusItens(StatusAchado status) {
        return tabela.index(AchadoItem.INDICE_STATUS_SEVERIDADE)
                .query(r -> r.queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(status.name()).build())))
                .stream().flatMap(pagina -> pagina.items().stream()).toList();
    }

    /**
     * Uma página de achados de um status, do mais grave para o menos grave, pelo índice secundário (status + severidade).
     * O cursor é a chave onde a página anterior parou.
     *
     * @param minima só achados com esta severidade ou mais grave ({@code null} = todas)
     * @throws IllegalArgumentException se o cursor for inválido
     */
    public PaginaDeItens listar(StatusAchado status, Severidade minima, int limite, String cursor) {
        QueryConditional condicao = minima == null
                ? QueryConditional.keyEqualTo(Key.builder().partitionValue(status.name()).build())
                : QueryConditional.sortGreaterThanOrEqualTo(
                        Key.builder().partitionValue(status.name()).sortValue(minima.ordinal()).build());
        var pedido = QueryEnhancedRequest.builder().queryConditional(condicao).scanIndexForward(false).limit(limite);
        if (cursor != null && !cursor.isBlank()) {
            pedido.exclusiveStartKey(decodificar(cursor));
        }
        Page<AchadoItem> pagina;
        try {
            pagina = tabela.index(AchadoItem.INDICE_STATUS_SEVERIDADE).query(pedido.build()).iterator().next();
        } catch (software.amazon.awssdk.services.dynamodb.model.DynamoDbException e) {
            if (cursor != null && !cursor.isBlank()) {
                throw new IllegalArgumentException("cursor inválido", e); // uma chave que não é desta consulta
            }
            throw e;
        }
        String proximo = pagina.lastEvaluatedKey() == null ? null : codificar(pagina.lastEvaluatedKey());
        return new PaginaDeItens(pagina.items(), proximo);
    }

    private static String codificar(Map<String, AttributeValue> chave) {
        String texto = String.join("\u0000", chave.get("arn").s(), chave.get("regra").s(), chave.get("status").s(),
                chave.get("severidadeNivel").n());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, AttributeValue> decodificar(String cursor) {
        try {
            String[] partes = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\u0000", -1);
            if (partes.length != 4) {
                throw new IllegalArgumentException("cursor inválido");
            }
            Integer.parseInt(partes[3]);
            return Map.of("arn", s(partes[0]), "regra", s(partes[1]), "status", s(partes[2]),
                    "severidadeNivel", AttributeValue.builder().n(partes[3]).build());
        } catch (IllegalArgumentException e) { // Base64 e número inválidos também são IllegalArgumentException
            throw new IllegalArgumentException("cursor inválido", e);
        }
    }

    private boolean criar(Achado achado, long ms) {
        var item = new AchadoItem();
        item.setArn(achado.arn());
        item.setRegra(achado.regra());
        item.setStatus(StatusAchado.ABERTO);
        item.setSeveridade(achado.severidade());
        item.setSeveridadeNivel(achado.severidade().ordinal());
        item.setDescricao(achado.descricao());
        item.setPrimeiraVezVisto(ms);
        item.setUltimaVezVisto(ms);
        try {
            tabela.putItem(PutItemEnhancedRequest.builder(AchadoItem.class).item(item)
                    .conditionExpression(Expression.builder().expression("attribute_not_exists(arn)").build()).build());
            return true;
        } catch (ConditionalCheckFailedException jaExiste) {
            return false;
        }
    }

    /** Atualiza o "visto por último" (e a descrição), só se o status é o esperado e a data é mais nova que a gravada. */
    private void atualizarVisto(Achado achado, long ms, StatusAchado esperado) {
        atualizar(achado.arn(), achado.regra(),
                "SET ultimaVezVisto = :agora, descricao = :descricao, severidade = :severidade, severidadeNivel = :nivel",
                "#s = :esperado AND ultimaVezVisto < :agora",
                Map.of(":agora", n(ms), ":descricao", s(achado.descricao()), ":severidade", s(achado.severidade().name()),
                        ":nivel", n(achado.severidade().ordinal()), ":esperado", s(esperado.name())));
    }

    private boolean reabrir(Achado achado, long ms) {
        return atualizar(achado.arn(), achado.regra(),
                "SET #s = :aberto, ultimaVezVisto = :agora, reabertoEm = :agora, descricao = :descricao, "
                        + "severidade = :severidade, severidadeNivel = :nivel REMOVE resolvidoEm",
                "#s = :resolvido",
                Map.of(":agora", n(ms), ":descricao", s(achado.descricao()), ":severidade", s(achado.severidade().name()),
                        ":nivel", n(achado.severidade().ordinal()), ":aberto", s(StatusAchado.ABERTO.name()),
                        ":resolvido", s(StatusAchado.RESOLVIDO.name())));
    }

    private boolean resolver(AchadoItem existente, long ms) {
        return atualizar(existente.getArn(), existente.getRegra(), "SET #s = :resolvido, resolvidoEm = :agora",
                "#s = :aberto AND ultimaVezVisto < :agora",
                Map.of(":agora", n(ms), ":aberto", s(StatusAchado.ABERTO.name()),
                        ":resolvido", s(StatusAchado.RESOLVIDO.name())));
    }

    /** @return false se a condição não valeu (alguém já mudou o item), o que NÃO é erro */
    private boolean atualizar(String arn, String regra, String expressao, String condicao,
            Map<String, AttributeValue> valores) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder().tableName(tabela.tableName())
                    .key(Map.of("arn", s(arn), "regra", s(regra))).updateExpression(expressao)
                    .conditionExpression(condicao).expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(valores).build());
            return true;
        } catch (ConditionalCheckFailedException condicaoNaoValeu) {
            return false;
        }
    }

    private static AttributeValue s(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    private static AttributeValue n(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }

    private static String chave(String arn, String regra) {
        return arn + "\u0000" + regra;
    }

    static Achado paraDominio(AchadoItem i) {
        return new Achado(i.getRegra(), i.getSeveridade(), i.getArn(), i.getDescricao(),
                Instant.ofEpochMilli(i.getPrimeiraVezVisto()), i.getStatus());
    }
}
