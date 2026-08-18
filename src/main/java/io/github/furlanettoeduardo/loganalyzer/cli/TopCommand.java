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
import java.util.function.Function;
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

    /**
     * Aceita nivel-logger além de NIVEL_LOGGER. Lança de propósito: argumento de CLI
     * errado é erro do usuário, e o picocli precisa da exceção para imprimir o usage.
     * Contraste com Level.parse, que devolve Optional porque log ruim é caso esperado.
     */
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
            Stream<ParseResult> resultados = linhas.map(parser::parse);

            // K é inferido em cada ramo: String, Level, Chave. Nenhum Object no caminho.
            // O desempate vem de fora: ordem é decisão de quem apresenta, não do dado.
            switch (by) {
                case LOGGER       -> imprimir(contar(resultados, LogEntry::logger),
                                              Comparator.naturalOrder());
                case NIVEL        -> imprimir(contar(resultados, LogEntry::nivel),
                                              Comparator.naturalOrder());
                // lambda com parâmetro tipado: uma lambda implícita (e -> ...) não
                // participa da inferência do K aninhado, e o compilador cai em Object
                case NIVEL_LOGGER -> imprimir(contar(resultados,
                                                     (LogEntry e) -> new Chave(e.nivel(), e.logger())),
                                              Comparator.comparing(Chave::nivel)
                                                        .thenComparing(Chave::logger));
            }
        }

        return 0;
    }

    private <K> Map<K, Long> contar(Stream<ParseResult> resultados, Function<LogEntry, K> chave) {
        // mapMulti filtra e transforma num passo: o consumidor só recebe o que interessa
        return resultados
                .<LogEntry>mapMulti((resultado, consumidor) -> {
                    if (resultado instanceof ParseResult.Ok(LogEntry entry)) {
                        consumidor.accept(entry);
                    }
                })
                .collect(Collectors.groupingBy(chave, Collectors.counting()));
    }

    /**
     * O desempate chega como Comparator, não como bound Comparable: assim o Chave não
     * precisa inventar uma ordem natural que não tem.
     *
     * <p>{@code Comparator<? super K>} e não {@code Comparator<K>} porque um comparador
     * de supertipo serve: um {@code Comparator<Object>} (comparar por toString, por
     * exemplo) ordena Chave perfeitamente. Comparator consome K, e parâmetro que consome
     * aceita supertipo — o "super" do PECS.
     */
    private <K> void imprimir(Map<K, Long> contagem, Comparator<? super K> desempate) {
        System.out.printf("top %d por %s (%d agrupamentos)%n",
                limit, by.name().toLowerCase(Locale.ROOT).replace('_', '-'), contagem.size());

        // desempate pela chave: sem isso, empates saem em ordem imprevisível entre execuções
        contagem.entrySet().stream()
                .sorted(Map.Entry.<K, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey, desempate))
                .limit(limit)
                .forEach(entrada -> System.out.printf("%9d  %s%n", entrada.getValue(), entrada.getKey()));
    }
}
