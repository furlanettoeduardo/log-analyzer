package io.github.furlanettoeduardo.loganalyzer.cli;

import io.github.furlanettoeduardo.loganalyzer.cli.WindowCommand.Janela;
import io.github.furlanettoeduardo.loganalyzer.domain.LogParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowCommandTest {

    private final LogParser parser = new LogParser();

    @Test
    void janela_ancora_no_epoch_e_aceita_tamanho_arbitrario() {
        Instant momento = Instant.parse("2026-08-14T09:07:33.482Z");

        assertThat(WindowCommand.inicioDaJanela(momento, Duration.ofMinutes(1)))
                .isEqualTo(Instant.parse("2026-08-14T09:07:00Z"));
        assertThat(WindowCommand.inicioDaJanela(momento, Duration.ofSeconds(30)))
                .isEqualTo(Instant.parse("2026-08-14T09:07:30Z"));
        assertThat(WindowCommand.inicioDaJanela(momento, Duration.ofMinutes(5)))
                .isEqualTo(Instant.parse("2026-08-14T09:05:00Z"));
        // 7 minutos não divide o dia. A janela não cai em 09:05 (que seria ancorar no
        // dia) e sim em 09:01, porque floorDiv ancora no epoch: 29.778.301 minutos
        // desde 1970-01-01 é múltiplo de 7. É por isso que truncatedTo recusa a unidade —
        // ver o teste abaixo.
        assertThat(WindowCommand.inicioDaJanela(momento, Duration.ofMinutes(7)))
                .isEqualTo(Instant.parse("2026-08-14T09:01:00Z"));
    }

    @Test
    void truncatedTo_recusa_unidade_que_nao_divide_o_dia() {
        Instant momento = Instant.parse("2026-08-14T09:07:33.482Z");

        // o que truncatedTo aceita: unidades que cabem no dia um número inteiro de vezes
        assertThat(momento.truncatedTo(ChronoUnit.MINUTES))
                .isEqualTo(Instant.parse("2026-08-14T09:07:00Z"));
        assertThat(momento.truncatedTo(ChronoUnit.HOURS))
                .isEqualTo(Instant.parse("2026-08-14T09:00:00Z"));

        assertThatThrownBy(() -> momento.truncatedTo(SETE_MINUTOS))
                .isInstanceOf(UnsupportedTemporalTypeException.class)
                .hasMessage("Unit must divide into a standard day without remainder");
    }

    private static final TemporalUnit SETE_MINUTOS = new TemporalUnit() {
        @Override public Duration getDuration() { return Duration.ofMinutes(7); }
        @Override public boolean isDurationEstimated() { return false; }
        @Override public boolean isDateBased() { return false; }
        @Override public boolean isTimeBased() { return true; }
        @Override public <R extends Temporal> R addTo(R temporal, long amount) {
            throw new UnsupportedOperationException();
        }
        @Override public long between(Temporal inicio, Temporal fim) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void agrega_latencia_por_janela_contando_tambem_as_linhas_sem_duracao() throws Exception {
        Path arquivo = arquivoTemporario("""
                2026-08-14T09:00:01.000Z INFO com.acme.X traceId=aaaa1111 msg="a" duration_ms=10
                2026-08-14T09:00:59.999Z WARN com.acme.X traceId=bbbb2222 msg="b" duration_ms=30
                2026-08-14T09:00:30.000Z INFO com.acme.X traceId=cccc3333 msg="sem duracao"
                2026-08-14T09:01:00.000Z INFO com.acme.X traceId=dddd4444 msg="d" duration_ms=100
                """);

        Map<Instant, Janela> janelas = agregar(arquivo, false);

        assertThat(janelas).hasSize(2);
        Janela primeira = janelas.get(Instant.parse("2026-08-14T09:00:00Z"));
        assertThat(primeira.linhas()).isEqualTo(3);              // conta a linha sem duração
        assertThat(primeira.latencia().amostras()).isEqualTo(2); // mas ela não vira amostra
        assertThat(primeira.latencia().min()).isEqualTo(Duration.ofMillis(10));
        assertThat(primeira.latencia().max()).isEqualTo(Duration.ofMillis(30));
        assertThat(primeira.latencia().media()).isEqualTo(Duration.ofMillis(20));

        Janela segunda = janelas.get(Instant.parse("2026-08-14T09:01:00Z"));
        assertThat(segunda.linhas()).isEqualTo(1);
        assertThat(segunda.latencia().p99()).isEqualTo(Duration.ofMillis(100));

        Files.deleteIfExists(arquivo);
    }

    @Test
    void ingenuo_e_collector_produzem_o_mesmo_mapa() throws Exception {
        Path arquivo = arquivoGerado(2000);

        assertThat(agregar(arquivo, true)).isEqualTo(agregar(arquivo, false));

        Files.deleteIfExists(arquivo);
    }

    @Test
    void paralelo_produz_o_mesmo_mapa_que_sequencial() throws Exception {
        Path arquivo = arquivoGerado(2000);

        Map<Instant, Janela> sequencial;
        Map<Instant, Janela> paralelo;
        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            sequencial = WindowCommand.agregar(linhas, parser, Duration.ofMinutes(1));
        }
        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            paralelo = WindowCommand.agregar(linhas.parallel(), parser, Duration.ofMinutes(1));
        }

        assertThat(paralelo).isEqualTo(sequencial);
        Files.deleteIfExists(arquivo);
    }

    private Map<Instant, Janela> agregar(Path arquivo, boolean ingenuo) throws Exception {
        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            return ingenuo
                    ? WindowCommand.agregarIngenuo(linhas, parser, Duration.ofMinutes(1))
                    : WindowCommand.agregar(linhas, parser, Duration.ofMinutes(1));
        }
    }

    private Path arquivoTemporario(String conteudo) throws Exception {
        Path arquivo = Files.createTempFile("window", ".log");
        Files.writeString(arquivo, conteudo, StandardCharsets.UTF_8);
        return arquivo;
    }

    /** Linhas determinísticas, algumas sem duration_ms, espalhadas por várias janelas. */
    private Path arquivoGerado(int quantas) throws Exception {
        Instant base = Instant.parse("2026-08-14T09:00:00Z");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < quantas; i++) {
            Instant momento = base.plusMillis(i * 137L);
            sb.append(momento).append(" INFO com.acme.X traceId=abc").append(String.format("%05x", i))
                    .append(" msg=\"op ").append(i).append('"');
            if (i % 7 != 0) {
                sb.append(" duration_ms=").append((i * 31) % 2000);
            }
            sb.append('\n');
        }
        return arquivoTemporario(sb.toString());
    }
}
