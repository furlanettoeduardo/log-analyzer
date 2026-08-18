package io.github.furlanettoeduardo.loganalyzer.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public record LogEntry(
        Instant timestamp,
        Level nivel,
        String logger,
        String traceId,
        String mensagem,
        // Optional: campo legitimamente ausente (15% das linhas não têm duration_ms).
        // Conceito diferente de Malformed, que é "não deu para ler a linha".
        Optional<Duration> duracao
) {}
