package io.github.furlanettoeduardo.loganalyzer;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "loganalyzer",
        mixinStandardHelpOptions = true,
        version = "loganalyzer 0.1.0",
        description = "Analisa arquivos de log e agrega métricas."
)
public class LogAnalyzer implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogAnalyzer()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        System.out.println("Cadeia funcionando. Use --help.");
    }
}