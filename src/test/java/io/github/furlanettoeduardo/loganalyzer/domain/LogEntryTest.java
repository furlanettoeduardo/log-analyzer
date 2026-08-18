package io.github.furlanettoeduardo.loganalyzer.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LogEntryTest {

    @Test
    void deve_comparar_campo_a_campo() {
        LogEntry a = new LogEntry(Instant.parse("2026-08-14T09:12:33.482Z"), Level.INFO, "com.acme.api.ReservaController",
                "7f3a2b91", "reserva criada", Optional.of(Duration.ofMillis(142)));
        LogEntry b = new LogEntry(Instant.parse("2026-08-14T09:12:33.482Z"), Level.INFO, "com.acme.api.ReservaController",
                "7f3a2b91", "reserva criada", Optional.of(Duration.ofMillis(142)));

        // record: equals por valor (e hashCode coerente) — é o que faz groupingBy funcionar
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotSameAs(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
