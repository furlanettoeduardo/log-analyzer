package io.github.furlanettoeduardo.loganalyzer.domain;

import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult.Motivo;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {

    // [0-9a-f]+ descreve o formato do valor; \S+ seria greedy e engoliria
    // o que viesse colado depois (traceId=abc123,extra)
    private static final Pattern TRACE_ID = Pattern.compile("traceId=([0-9a-f]+)");
    private static final Pattern MENSAGEM = Pattern.compile("msg=\"([^\"]*)\"");
    private static final Pattern DURACAO = Pattern.compile("duration_ms=(\\d+)");

    public ParseResult parse(String linha) {
        // limite 4: corta timestamp, nível e logger, e devolve o resto inteiro
        // no último elemento (msg="..." tem espaço dentro e não pode ser fatiado)
        String[] partes = linha.trim().split("\\s+", 4);
        if (partes.length < 4) {
            return new ParseResult.Malformed(linha, Motivo.ESTRUTURA_INVALIDA);
        }

        Optional<Level> nivel = Level.parse(partes[1]);
        if (nivel.isEmpty()) {
            return new ParseResult.Malformed(linha, Motivo.NIVEL_DESCONHECIDO);
        }

        String resto = partes[3];

        Matcher traceId = TRACE_ID.matcher(resto);
        if (!traceId.find()) {
            return new ParseResult.Malformed(linha, Motivo.TRACE_ID_AUSENTE);
        }

        Matcher mensagem = MENSAGEM.matcher(resto);
        if (!mensagem.find()) {
            return new ParseResult.Malformed(linha, Motivo.MENSAGEM_AUSENTE);
        }

        Matcher duracao = DURACAO.matcher(resto);
        Optional<String> duracaoValor = duracao.find()
                ? Optional.of(duracao.group(1))
                : Optional.empty();

        return new ParseResult.Ok(new LogEntry(
                partes[0],
                nivel.get(),
                partes[2],
                traceId.group(1),
                mensagem.group(1),
                duracaoValor
        ));
    }
}
