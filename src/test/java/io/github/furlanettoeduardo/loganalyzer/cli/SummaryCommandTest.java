package io.github.furlanettoeduardo.loganalyzer.cli;

import io.github.furlanettoeduardo.loganalyzer.cli.SummaryCommand.Resumo;
import io.github.furlanettoeduardo.loganalyzer.domain.Level;
import io.github.furlanettoeduardo.loganalyzer.domain.LogParser;
import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult.Motivo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryCommandTest {

    private static final int LINHAS_DO_SAMPLE = 31;

    private final LogParser parser = new LogParser();

    @Test
    void nenhuma_linha_se_perde_no_modo_sequencial() throws Exception {
        Resumo resumo = resumir(false);

        assertThat(resumo.total()).isEqualTo(LINHAS_DO_SAMPLE);
        assertThat(resumo.totalValidas() + resumo.totalMalformadas()).isEqualTo(LINHAS_DO_SAMPLE);
    }

    @Test
    void paralelo_produz_exatamente_o_mesmo_resumo() throws Exception {
        Resumo sequencial = resumir(false);
        Resumo paralelo = resumir(true);

        // record equals compara campo a campo, então isto compara os dois mapas inteiros
        assertThat(paralelo).isEqualTo(sequencial);
        assertThat(paralelo.total()).isEqualTo(LINHAS_DO_SAMPLE);
    }

    @Test
    void conta_cada_nivel_pelo_campo_e_nao_pela_mensagem() throws Exception {
        Resumo resumo = resumir(false);

        assertThat(resumo.porNivel())
                .containsEntry(Level.INFO, 11L)
                .containsEntry(Level.WARN, 6L)
                .containsEntry(Level.ERROR, 9L)
                .containsEntry(Level.DEBUG, 5L)
                .doesNotContainKey(Level.TRACE);
        assertThat(resumo.malformadas()).isEmpty();
    }

    @Test
    void linha_corrompida_entra_como_malformada_com_motivo() throws Exception {
        Path arquivo = Files.createTempFile("log-corrompido", ".log");
        Files.writeString(arquivo, """
                2026-08-14T09:00:00.181Z WARN com.acme.X traceId=1fe52c4e msg="ok"
                ### linha corrompida ###
                """, StandardCharsets.UTF_8);

        try (Stream<String> linhas = Files.lines(arquivo, StandardCharsets.UTF_8)) {
            Resumo resumo = SummaryCommand.resumir(linhas, parser);

            assertThat(resumo.total()).isEqualTo(2);
            // "###" falha já no Instant.parse, antes de chegar no nível
            assertThat(resumo.malformadas()).containsExactly(
                    java.util.Map.entry(Motivo.TIMESTAMP_INVALIDO, 1L));
        } finally {
            Files.deleteIfExists(arquivo);
        }
    }

    private Resumo resumir(boolean paralelo) throws Exception {
        Path sample = Path.of(getClass().getResource("/sample.log").toURI());
        try (Stream<String> linhas = Files.lines(sample, StandardCharsets.UTF_8)) {
            return SummaryCommand.resumir(paralelo ? linhas.parallel() : linhas, parser);
        }
    }
}
