package dev.heldem.aws_auditor.persistencia;

/**
 * O que a execução mudou em relação à anterior.
 *
 * @param novos achados que nunca tinham aparecido
 * @param persistentes continuam abertos (e os que outra execução simultânea criou ou reabriu primeiro)
 * @param resolvidos estavam abertos e não aparecem mais: viram RESOLVIDO, com a data, e não são apagados
 * @param reabertos estavam resolvidos e voltaram a aparecer
 * @param ignorados achados que um humano mandou ignorar e que continuam aparecendo (o status não muda)
 */
public record ResultadoDoDiff(int novos, int persistentes, int resolvidos, int reabertos, int ignorados) {
}
