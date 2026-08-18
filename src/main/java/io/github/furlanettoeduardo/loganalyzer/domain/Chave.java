package io.github.furlanettoeduardo.loganalyzer.domain;

/**
 * Chave composta de agrupamento. Sendo record, ganha equals/hashCode por valor —
 * que é o contrato exigido por HashMap e, portanto, por Collectors.groupingBy.
 */
public record Chave(Level nivel, String logger) {

    @Override
    public String toString() {
        return nivel + " " + logger;
    }
}
