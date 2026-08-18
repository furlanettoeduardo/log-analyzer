package io.github.furlanettoeduardo.loganalyzer;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import io.github.furlanettoeduardo.loganalyzer.cli.SummaryCommand;
import io.github.furlanettoeduardo.loganalyzer.cli.TopCommand;
import io.github.furlanettoeduardo.loganalyzer.cli.WindowCommand;

@Command(
        name = "loganalyzer",
        mixinStandardHelpOptions = true,
        version = "loganalyzer 0.1.0",
        description = "Analisa arquivos de log e agrega métricas.",
        subcommands = { SummaryCommand.class, TopCommand.class, WindowCommand.class }
)
public class LogAnalyzer implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogAnalyzer()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}