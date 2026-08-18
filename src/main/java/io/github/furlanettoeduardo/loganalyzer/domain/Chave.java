package io.github.furlanettoeduardo.loganalyzer.domain;

import java.util.Comparator;

/**
 * Chave composta de agrupamento. Sendo record, ganha equals/hashCode por valor —
 * que é o contrato exigido por HashMap e, portanto, por Collectors.groupingBy.
 *
 * <p>Comparable, ao contrário, não vem de graça: record não implica ordem natural.
 * Precisa estar aqui para a chave poder ser usada em desempate genérico
 * ({@code <K extends Comparable<? super K>>}), como String e Level já são.
 */
public record Chave(Level nivel, String logger) implements Comparable<Chave> {

    private static final Comparator<Chave> ORDEM =
            Comparator.comparing(Chave::nivel).thenComparing(Chave::logger);

    @Override
    public int compareTo(Chave outra) {
        return ORDEM.compare(this, outra);
    }

    @Override
    public String toString() {
        return nivel + " " + logger;
    }
}
