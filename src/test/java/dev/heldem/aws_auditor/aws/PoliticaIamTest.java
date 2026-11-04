package dev.heldem.aws_auditor.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A policy IAM mínima do repositório tem EXATAMENTE as ações que os coletores usam: nem uma a mais (princípio do menor
 * privilégio), nem uma a menos (a varredura falharia com AccessDenied). Só ações de leitura (Describe, List, Get).
 */
class PoliticaIamTest {

    static final Path POLITICA = Path.of("docs/iam/politica-auditor-leitura.json");

    @Test
    void politicaTemExatamenteAsAcoesDosColetoresESoLeitura() throws IOException {
        var clientes = mock(ClientesAws.class);
        List<Coletor> coletores = List.of(new ColetorDeVolumes(clientes), new ColetorDeSecurityGroups(clientes),
                new ColetorDeBuckets(clientes), new ColetorDeChavesIam(clientes));
        Set<String> usadas = new TreeSet<>();
        coletores.forEach(c -> usadas.addAll(c.acoesIam()));

        JsonNode politica = JsonMapper.builder().build().readTree(Files.readString(POLITICA));
        Set<String> daPolitica = new TreeSet<>();
        politica.get("Statement").forEach(declaracao -> {
            assertThat(declaracao.get("Effect").asString()).isEqualTo("Allow");
            declaracao.get("Action").forEach(acao -> daPolitica.add(acao.asString()));
        });

        assertThat(daPolitica).containsExactlyElementsOf(usadas);
        assertThat(daPolitica).allMatch(a -> a.matches("[a-z0-9]+:(Describe|List|Get)[A-Za-z]+"),
                "só ações de leitura: Describe, List ou Get");
        assertThat(daPolitica).noneMatch(a -> a.contains("*")); // sem curinga de ação
    }

    @Test
    void politicaNaoTemIdDeContaNemArnReal() throws IOException {
        String texto = Files.readString(POLITICA);
        assertThat(texto).doesNotContainPattern("\\d{12}");
    }
}
