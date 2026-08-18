package io.github.furlanettoeduardo.loganalyzer.cli;

import io.github.furlanettoeduardo.loganalyzer.domain.Amostras;
import io.github.furlanettoeduardo.loganalyzer.domain.Estatisticas;
import io.github.furlanettoeduardo.loganalyzer.domain.LogEntry;
import io.github.furlanettoeduardo.loganalyzer.domain.LogParser;
import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(
        name = "window",
        mixinStandardHelpOptions = true,
        description = "Agrega latência por janela de tempo."
)
public class WindowCommand implements Callable<Integer> {

    /** Uma janela: quantas linhas caíram nela e as estatísticas das que têm duração. */
    public record Janela(long linhas, Estatisticas latencia) {}

    @Parameters(index = "0", description = "Arquivo de log a analisar.")
    private Path arquivo;

    @Option(names = "--size", defaultValue = "1m", converter = TamanhoConverter.class,
            description = "Tamanho da janela: 30s, 1m, 5m, 1h (padrão: ${DEFAULT-VALUE}).")
    private Duration tamanho;

    @Option(names = "--limit", defaultValue = "10",
            description = "Quantas janelas mostrar (padrão: ${DEFAULT-VALUE}).")
    private int limit;

    @Option(names = "--naive",
            description = "Usa o caminho ingênuo (List<Duration> por janela) em vez do Collector.")
    private boolean ingenuo;

    @Option(names = "--parallel", description = "Processa o stream em paralelo.")
    private boolean paralelo;

    private final LogParser parser = new LogParser();

    static class TamanhoConverter implements ITypeConverter<Duration> {
        @Override
        public Duration convert(String valor) {
            return Duration.parse("PT" + valor.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.isReadable(arquivo)) {
            System.err.println("Arquivo não encontrado ou sem permissão: " + arquivo);
            return 2;
        }

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            Stream<String> fonte = paralelo ? linhas.parallel() : linhas;
            imprimir(ingenuo
                    ? agregarIngenuo(fonte, parser, tamanho)
                    : agregar(fonte, parser, tamanho));
        }

        return 0;
    }

    /**
     * Caminho bom: o Collector customizado acumula em long[] e devolve as estatísticas
     * já calculadas. teeing junta a contagem de linhas com as estatísticas de latência,
     * e Optional.stream() descarta as linhas sem duration_ms sem cast nem filtro solto.
     */
    static Map<Instant, Janela> agregar(Stream<String> linhas, LogParser parser, Duration tamanho) {
        return entradas(linhas, parser).collect(Collectors.groupingBy(
                entry -> inicioDaJanela(entry.timestamp(), tamanho),
                TreeMap::new,
                Collectors.teeing(
                        Collectors.counting(),
                        Collectors.flatMapping(entry -> entry.duracao().stream(), Amostras.coletor()),
                        Janela::new)));
    }

    /**
     * Caminho ingênuo: mesma estrutura, mas materializa List&lt;Duration&gt; por janela e só
     * então ordena e indexa. A única diferença é o collector de baixo — é o A/B honesto.
     */
    static Map<Instant, Janela> agregarIngenuo(Stream<String> linhas, LogParser parser, Duration tamanho) {
        return entradas(linhas, parser).collect(Collectors.groupingBy(
                entry -> inicioDaJanela(entry.timestamp(), tamanho),
                TreeMap::new,
                Collectors.teeing(
                        Collectors.counting(),
                        Collectors.flatMapping(entry -> entry.duracao().stream(), Collectors.toList()),
                        (quantasLinhas, duracoes) -> new Janela(quantasLinhas, Estatisticas.deLista(duracoes)))));
    }

    private static Stream<LogEntry> entradas(Stream<String> linhas, LogParser parser) {
        return linhas.map(parser::parse)
                .<LogEntry>mapMulti((resultado, consumidor) -> {
                    if (resultado instanceof ParseResult.Ok(LogEntry entry)) {
                        consumidor.accept(entry);
                    }
                });
    }

    /**
     * Início da janela por aritmética sobre o epoch. Não dá para usar
     * {@code instant.truncatedTo(unidade)}: ele exige unidade que divida o dia sem resto
     * (por isso aceita MINUTES e HOURS, mas recusa 7min ou 45s), já que a truncagem é
     * definida a partir do início do dia. floorDiv ancora no epoch e aceita qualquer
     * tamanho — inclusive negativos, onde floorDiv arredonda para baixo e não para zero.
     */
    static Instant inicioDaJanela(Instant momento, Duration tamanho) {
        long tamanhoMs = tamanho.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(momento.toEpochMilli(), tamanhoMs) * tamanhoMs);
    }

    private void imprimir(Map<Instant, Janela> janelas) {
        System.out.printf("janelas de %s: %d  (mostrando %d, %s)%n",
                formatarTamanho(tamanho), janelas.size(), Math.min(limit, janelas.size()),
                ingenuo ? "caminho ingênuo" : "Collector customizado");
        System.out.printf("%-26s %8s %10s %9s %9s %9s %9s %9s%n",
                "janela", "linhas", "amostras", "min", "média", "p95", "p99", "max");

        janelas.entrySet().stream().limit(limit).forEach(entrada -> {
            Estatisticas e = entrada.getValue().latencia();
            System.out.printf("%-26s %8d %10d %9s %9s %9s %9s %9s%n",
                    entrada.getKey(), entrada.getValue().linhas(), e.amostras(),
                    ms(e.min()), ms(e.media()), ms(e.p95()), ms(e.p99()), ms(e.max()));
        });
    }

    /** Duration.toString() devolve PT0.142S; para log o que serve é 142ms. */
    private static String ms(Duration duracao) {
        return duracao.toMillis() + "ms";
    }

    private static String formatarTamanho(Duration tamanho) {
        return tamanho.toString().substring(2).toLowerCase(Locale.ROOT);
    }
}
