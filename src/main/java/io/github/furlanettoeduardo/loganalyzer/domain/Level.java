package io.github.furlanettoeduardo.loganalyzer.domain;

import java.util.Arrays;
import java.util.Optional;

public enum Level {
    TRACE, DEBUG, INFO, WARN, ERROR;

    /**
     * Converte texto em nível sem lançar exceção: nível desconhecido (FATAL, lixo,
     * null) devolve Optional.empty(), e a ausência fica explícita no tipo de retorno.
     */
    public static Optional<Level> parse(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String limpo = texto.trim();
        // values() devolve uma cópia defensiva a cada chamada (array é mutável em Java).
        // Candidato a Map<String, Level> pré-computado se o profiling do Bloco 13 apontar.
        return Arrays.stream(values())
                .filter(nivel -> nivel.name().equalsIgnoreCase(limpo))
                .findFirst();
    }
}
