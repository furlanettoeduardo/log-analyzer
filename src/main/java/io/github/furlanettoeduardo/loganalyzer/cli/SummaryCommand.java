package io.github.furlanettoeduardo.loganalyzer.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(
        name = "summary",
        description = "Conta linhas e agrega totais por nível."
)
public class SummaryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Arquivo de log a analisar.")
    private Path arquivo;

    private int total;
    private int info;
    private int warn;
    private int error;
    private int debug;
    private int naoClassificadas;

    @Override
    public Integer call() throws Exception {
        if (!Files.isReadable(arquivo)) {
            System.err.println("Arquivo não encontrado ou sem permissão: " + arquivo);
            return 2;
        }

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            linhas.forEach(linha -> {
                total++;
                if (linha.contains(" INFO ")) {
                    info++;
                } else if (linha.contains(" WARN ")) {
                    warn++;
                } else if (linha.contains(" ERROR ")) {
                    error++;
                } else if (linha.contains(" DEBUG ")) {
                    debug++;
                } else {
                    naoClassificadas++;
                }
            });
        }

        System.out.println("Total de linhas: " + total);
        System.out.println("INFO:  " + info);
        System.out.println("WARN:  " + warn);
        System.out.println("ERROR: " + error);
        System.out.println("DEBUG: " + debug);
        System.out.println("Não classificadas: " + naoClassificadas);

        return 0;
    }
}
