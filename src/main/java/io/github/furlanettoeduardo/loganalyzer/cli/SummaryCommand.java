package io.github.furlanettoeduardo.loganalyzer.cli;

import io.github.furlanettoeduardo.loganalyzer.domain.Level;
import io.github.furlanettoeduardo.loganalyzer.domain.LogEntry;
import io.github.furlanettoeduardo.loganalyzer.domain.LogParser;
import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult;
import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult.Motivo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(
        name = "summary",
        mixinStandardHelpOptions = true,
        description = "Conta linhas e agrega totais por nível."
)
public class SummaryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Arquivo de log a analisar.")
    private Path arquivo;

    @Option(names = "--parallel", description = "Processa o stream em paralelo.")
    private boolean paralelo;

    private final LogParser parser = new LogParser();

    /** As duas contagens que interessam, produzidas num único passo pelo stream. */
    public record Resumo(Map<Level, Long> porNivel, Map<Motivo, Long> malformadas) {

        public long totalValidas() {
            return soma(porNivel);
        }

        public long totalMalformadas() {
            return soma(malformadas);
        }

        public long total() {
            return totalValidas() + totalMalformadas();
        }

        private static long soma(Map<?, Long> mapa) {
            return mapa.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * teeing resolve o "preciso de duas coisas e collect só devolve uma": alimenta os
     * dois collectors com o mesmo elemento e funde os resultados no fim. Cada lado usa
     * flatMapping para ficar só com o caso que lhe interessa — sem estado mutável, o que
     * é o que permite rodar em paralelo sem mudar uma linha.
     *
     * <p>Nota de custo: Stream.of/Stream.empty() aloca dois streams efêmeros por linha
     * (1 milhão em 500 mil linhas). Candidato a Collectors.filtering + Collectors.mapping
     * se o profiling do Bloco 13 apontar — não antes, que seria otimizar sem medir.
     */
    private static final Collector<ParseResult, ?, Resumo> RESUMO = Collectors.teeing(
            Collectors.flatMapping(SummaryCommand::apenasEntry,
                    Collectors.groupingBy(LogEntry::nivel,
                            () -> new EnumMap<Level, Long>(Level.class),
                            Collectors.counting())),
            Collectors.flatMapping(SummaryCommand::apenasMotivo,
                    Collectors.groupingBy(Function.identity(),
                            () -> new EnumMap<Motivo, Long>(Motivo.class),
                            Collectors.counting())),
            Resumo::new);

    @Override
    public Integer call() throws Exception {
        if (!Files.isReadable(arquivo)) {
            System.err.println("Arquivo não encontrado ou sem permissão: " + arquivo);
            return 2;
        }

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            imprimir(resumir(paralelo ? linhas.parallel() : linhas, parser));
        }

        return 0;
    }

    /** A agregação isolada do IO e da impressão, para poder ser testada nos dois modos. */
    static Resumo resumir(Stream<String> linhas, LogParser parser) {
        return linhas.map(parser::parse).collect(RESUMO);
    }

    private static Stream<LogEntry> apenasEntry(ParseResult resultado) {
        return switch (resultado) {
            case ParseResult.Ok(LogEntry entry) -> Stream.of(entry);
            case ParseResult.Malformed malformed -> Stream.empty();
        };
    }

    private static Stream<Motivo> apenasMotivo(ParseResult resultado) {
        return switch (resultado) {
            case ParseResult.Ok ok -> Stream.empty();
            case ParseResult.Malformed(String linha, Motivo motivo) -> Stream.of(motivo);
        };
    }

    private void imprimir(Resumo resumo) {
        System.out.println("Total de linhas: " + resumo.total());
        for (Level nivel : Level.values()) {
            System.out.printf("%-6s %d%n", nivel + ":", resumo.porNivel().getOrDefault(nivel, 0L));
        }
        System.out.println("Malformadas: " + resumo.totalMalformadas());
        resumo.malformadas().forEach((motivo, quantas) ->
                System.out.printf("  %-20s %d%n", motivo, quantas));
    }
}
