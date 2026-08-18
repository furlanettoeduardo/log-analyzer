package io.github.furlanettoeduardo.loganalyzer.domain;

import io.github.furlanettoeduardo.loganalyzer.domain.ParseResult.Motivo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserTest {

    private final LogParser parser = new LogParser();

    @Test
    void deve_extrair_campos_de_linha_valida() {
        LogEntry entry = entryDe(
                "2026-08-14T09:12:33.482Z INFO  com.acme.api.ReservaController  traceId=7f3a2b91 msg=\"reserva criada\" duration_ms=142");

        assertThat(entry.timestamp()).isEqualTo("2026-08-14T09:12:33.482Z");
        assertThat(entry.nivel()).isEqualTo(Level.INFO);
        assertThat(entry.logger()).isEqualTo("com.acme.api.ReservaController");
        assertThat(entry.traceId()).isEqualTo("7f3a2b91");
        assertThat(entry.mensagem()).isEqualTo("reserva criada");
        assertThat(entry.duracao()).contains("142");
    }

    @Test
    void duracao_ausente_e_optional_vazio_nao_falha_de_parse() {
        LogEntry entry = entryDe(
                "2026-08-14T09:12:33.482Z DEBUG com.acme.infra.JpaReservaRepository traceId=725b5ec8 msg=\"op 4\"");

        assertThat(entry.nivel()).isEqualTo(Level.DEBUG);
        assertThat(entry.duracao()).isEmpty();
    }

    @Test
    void deve_extrair_traceId_no_fim_da_linha() {
        assertThat(entryDe("2026-08-14T09:12:33.482Z INFO com.acme.api.X msg=\"ok\" traceId=abc123").traceId())
                .isEqualTo("abc123");
    }

    @Test
    void nao_deve_engolir_campos_seguintes() {
        assertThat(entryDe("2026-08-14T09:12:33.482Z INFO com.acme.api.X traceId=abc123,extra msg=\"ok\"").traceId())
                .isEqualTo("abc123");
    }

    @Test
    void deve_usar_o_nivel_do_campo_e_nao_a_mensagem() {
        LogEntry entry = entryDe(
                "2026-08-14T09:00:05.000Z ERROR com.acme.api.PagamentoAdapter traceId=deadbeef msg=\"request INFO header invalido\"");

        assertThat(entry.nivel()).isEqualTo(Level.ERROR);
        assertThat(entry.mensagem()).isEqualTo("request INFO header invalido");
    }

    @Test
    void deve_distinguir_o_motivo_de_cada_falha() {
        assertThat(motivoDe("linha curta")).isEqualTo(Motivo.ESTRUTURA_INVALIDA);
        assertThat(motivoDe("### linha corrompida ###")).isEqualTo(Motivo.NIVEL_DESCONHECIDO);
        assertThat(motivoDe("2026-08-14T09:12:33.482Z FATAL com.acme.api.X traceId=abc123 msg=\"ok\""))
                .isEqualTo(Motivo.NIVEL_DESCONHECIDO);
        assertThat(motivoDe("2026-08-14T09:12:33.482Z INFO com.acme.api.X msg=\"sem trace\""))
                .isEqualTo(Motivo.TRACE_ID_AUSENTE);
        assertThat(motivoDe("2026-08-14T09:12:33.482Z INFO com.acme.api.X traceId=abc123 duration_ms=10"))
                .isEqualTo(Motivo.MENSAGEM_AUSENTE);
    }

    @Test
    void malformed_preserva_a_linha_original_para_diagnostico() {
        String linha = "### linha corrompida ###";

        assertThat(parser.parse(linha))
                .isEqualTo(new ParseResult.Malformed(linha, Motivo.NIVEL_DESCONHECIDO));
    }

    // switch exaustivo: sem default, o compilador garante que os dois casos estão cobertos
    private LogEntry entryDe(String linha) {
        return switch (parser.parse(linha)) {
            case ParseResult.Ok(LogEntry entry) -> entry;
            case ParseResult.Malformed(String texto, Motivo motivo) ->
                    throw new AssertionError("esperava Ok, veio " + motivo + " em: " + texto);
        };
    }

    private Motivo motivoDe(String linha) {
        return switch (parser.parse(linha)) {
            case ParseResult.Ok(LogEntry entry) -> throw new AssertionError("esperava Malformed, veio " + entry);
            case ParseResult.Malformed(String texto, Motivo motivo) -> motivo;
        };
    }
}
