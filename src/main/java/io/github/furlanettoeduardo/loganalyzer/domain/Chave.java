package io.github.furlanettoeduardo.loganalyzer.domain;

/**
 * Chave composta de agrupamento. Sendo record, ganha equals/hashCode por valor —
 * que é o contrato exigido por HashMap e, portanto, por Collectors.groupingBy.
 *
 * <p>De propósito NÃO implementa Comparable: igualdade tem definição natural (todos os
 * campos iguais), ordenação não — nível antes de logger, ou o contrário? Quem apresenta
 * decide, passando um Comparator. Ver TopCommand.imprimir.
 */
public record Chave(Level nivel, String logger) {

    @Override
    public String toString() {
        return nivel + " " + logger;
    }
}
