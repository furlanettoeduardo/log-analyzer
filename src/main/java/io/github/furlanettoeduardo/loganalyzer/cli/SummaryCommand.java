package io.github.furlanettoeduardo.loganalyzer.cli;

import io.github.furlanettoeduardo.loganalyzer.domain.Level;
import io.github.furlanettoeduardo.loganalyzer.domain.LogEntry;
import io.github.furlanettoeduardo.loganalyzer.domain.LogParser;
import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult;
import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult.Motivo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(
        name = "summary",
        description = "Conta linhas e agrega totais por nível."
)
public class SummaryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Arquivo de log a analisar.")
    private Path arquivo;

    private final LogParser parser = new LogParser();

    private long total;
    private long trace;
    private long debug;
    private long info;
    private long warn;
    private long error;
    private final Map<Motivo, Long> malformadas = new EnumMap<>(Motivo.class);

    @Override
    public Integer call() throws Exception {
        if (!Files.isReadable(arquivo)) {
            System.err.println("Arquivo não encontrado ou sem permissão: " + arquivo);
            return 2;
        }

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            linhas.forEach(linha -> {
                total++;
                // sem default, sem instanceof, sem cast: o compilador sabe que só há dois casos
                switch (parser.parse(linha)) {
                    case ParseResult.Ok(LogEntry entry) -> contar(entry.nivel());
                    case ParseResult.Malformed(String texto, Motivo motivo) -> registrarFalha(motivo);
                }
            });
        }

        imprimir();
        return 0;
    }

    private void contar(Level nivel) {
        switch (nivel) {
            case TRACE -> trace++;
            case DEBUG -> debug++;
            case INFO -> info++;
            case WARN -> warn++;
            case ERROR -> error++;
        }
    }

    private void registrarFalha(Motivo motivo) {
        malformadas.merge(motivo, 1L, Long::sum);
    }

    private void imprimir() {
        System.out.println("Total de linhas: " + total);
        System.out.println("TRACE: " + trace);
        System.out.println("DEBUG: " + debug);
        System.out.println("INFO:  " + info);
        System.out.println("WARN:  " + warn);
        System.out.println("ERROR: " + error);

        long totalMalformadas = malformadas.values().stream().mapToLong(Long::longValue).sum();
        System.out.println("Malformadas: " + totalMalformadas);
        malformadas.forEach((motivo, quantas) ->
                System.out.printf("  %-20s %d%n", motivo, quantas));
    }
}
