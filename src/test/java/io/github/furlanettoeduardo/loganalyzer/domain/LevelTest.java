package io.github.furlanettoeduardo.loganalyzer.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LevelTest {

    @Test
    void deve_converter_texto_conhecido() {
        assertThat(Level.parse("INFO")).contains(Level.INFO);
        assertThat(Level.parse("error")).contains(Level.ERROR);
        assertThat(Level.parse("  WARN  ")).contains(Level.WARN);
    }

    @Test
    void deve_devolver_vazio_para_nivel_desconhecido() {
        assertThat(Level.parse("FATAL")).isEmpty();
        assertThat(Level.parse("###")).isEmpty();
        assertThat(Level.parse("")).isEmpty();
        assertThat(Level.parse(null)).isEmpty();
    }

    @Test
    void constante_e_instancia_unica_entao_comparacao_por_referencia_vale() {
        Level doParse = Level.parse("INFO").orElseThrow();

        // com enum, == é correto: a JVM garante uma instância por constante
        assertThat(doParse == Level.INFO).isTrue();
    }

    @Test
    void values_expoe_todas_as_constantes() {
        assertThat(Level.values())
                .containsExactly(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);
    }

    @Test
    void parse_devolve_optional_e_nao_lanca_como_valueOf() {
        Optional<Level> resultado = Level.parse("FATAL");

        assertThat(resultado).isEmpty();
    }
}
