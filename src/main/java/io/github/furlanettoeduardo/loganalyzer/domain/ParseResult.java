package io.github.furlanettoeduardo.loganalyzer.domain;

/**
 * Resultado de uma tentativa de parse: ou a linha virou {@link LogEntry}, ou não deu,
 * e o motivo vem junto. Conjunto fechado — o compilador verifica exaustividade em switch.
 */
public sealed interface ParseResult {

    record Ok(LogEntry entry) implements ParseResult {}

    record Malformed(String linha, Motivo motivo) implements ParseResult {}

    enum Motivo {
        ESTRUTURA_INVALIDA,
        NIVEL_DESCONHECIDO,
        TRACE_ID_AUSENTE,
        MENSAGEM_AUSENTE
    }
}
