package io.github.furlanettoeduardo.loganalyzer.cli;

import io.github.furlanettoeduardo.loganalyzer.domain.Chave;
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
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(
        name = "top",
        mixinStandardHelpOptions = true,
        description = "Mostra os maiores agrupamentos do log."
)
public class TopCommand implements Callable<Integer> {

    public enum Criterio { LOGGER, NIVEL, NIVEL_LOGGER }

    @Parameters(index = "0", description = "Arquivo de log a analisar.")
    private Path arquivo;

    @Option(names = "--by", defaultValue = "logger", converter = CriterioConverter.class,
            description = "Agrupamento: logger, nivel ou nivel-logger (padrão: ${DEFAULT-VALUE}).")
    private Criterio by;

    @Option(names = "--limit", defaultValue = "10",
            description = "Quantos agrupamentos mostrar (padrão: ${DEFAULT-VALUE}).")
    private int limit;

    private final LogParser parser = new LogParser();

    /** Aceita nivel-logger além de NIVEL_LOGGER, que é o nome da constante. */
    static class CriterioConverter implements ITypeConverter<Criterio> {
        @Override
        public Criterio convert(String valor) {
            return Criterio.valueOf(valor.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.isReadable(arquivo)) {
            System.err.println("Arquivo não encontrado ou sem permissão: " + arquivo);
            return 2;
        }

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            // mapMulti filtra e transforma num passo: o consumidor só recebe o que interessa
            Map<Object, Long> contagem = linhas
                    .map(parser::parse)
                    .<LogEntry>mapMulti((resultado, consumidor) -> {
                        if (resultado instanceof ParseResult.Ok(LogEntry entry)) {
                            consumidor.accept(entry);
                        }
                    })
                    .collect(Collectors.groupingBy(this::chaveDe, Collectors.counting()));

            imprimir(contagem);
        }

        return 0;
    }

    private Object chaveDe(LogEntry entry) {
        return switch (by) {
            case LOGGER -> entry.logger();
            case NIVEL -> entry.nivel();
            case NIVEL_LOGGER -> new Chave(entry.nivel(), entry.logger());
        };
    }

    private void imprimir(Map<Object, Long> contagem) {
        System.out.printf("top %d por %s (%d agrupamentos)%n",
                limit, by.name().toLowerCase(Locale.ROOT).replace('_', '-'), contagem.size());

        // desempate pela chave: sem isso, empates saem em ordem imprevisível entre execuções
        contagem.entrySet().stream()
                .sorted(Map.Entry.<Object, Long>comparingByValue().reversed()
                        .thenComparing(entrada -> entrada.getKey().toString()))
                .limit(limit)
                .forEach(entrada -> System.out.printf("%9d  %s%n", entrada.getValue(), entrada.getKey()));
    }

    // Comparator explícito equivalente ao encadeamento acima, deixado como nota:
    // Comparator.comparing(Map.Entry<Object, Long>::getValue).reversed()
    //           .thenComparing(e -> e.getKey().toString())
    static final Comparator<Map.Entry<Object, Long>> POR_CONTAGEM_DESC =
            Map.Entry.<Object, Long>comparingByValue().reversed()
                    .thenComparing(entrada -> entrada.getKey().toString());
}
