package io.github.furlanettoeduardo.loganalyzer.domain;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ChaveTest {

    /**
     * O contrato que groupingBy exige da chave: objetos iguais têm que colidir no mesmo
     * balde. Uma classe comum sem equals/hashCode cairia em N grupos de 1 elemento.
     */
    @Test
    void chaves_iguais_caem_no_mesmo_grupo() {
        List<Chave> chaves = List.of(
                new Chave(Level.ERROR, "com.acme.api.X"),
                new Chave(Level.ERROR, "com.acme.api.X"),
                new Chave(Level.INFO, "com.acme.api.X"));

        Map<Chave, Long> agrupado = chaves.stream()
                .collect(Collectors.groupingBy(chave -> chave, Collectors.counting()));

        assertThat(agrupado).hasSize(2);
        assertThat(agrupado).containsEntry(new Chave(Level.ERROR, "com.acme.api.X"), 2L);
    }

    @Test
    void hashCode_e_consistente_com_equals() {
        Chave a = new Chave(Level.WARN, "com.acme.domain.ReservaService");
        Chave b = new Chave(Level.WARN, "com.acme.domain.ReservaService");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).hasToString("WARN com.acme.domain.ReservaService");
    }

    /**
     * Chave não é Comparable de propósito: não há ordem óbvia entre (ERROR, "A") e
     * (INFO, "B"). Quem ordena escolhe, e escolhas diferentes convivem.
     */
    @Test
    void a_ordem_vem_de_fora_e_pode_ser_mais_de_uma() {
        Chave erroApi = new Chave(Level.ERROR, "com.acme.api.X");
        Chave infoDominio = new Chave(Level.INFO, "com.acme.domain.Y");

        Comparator<Chave> porNivel = Comparator.comparing(Chave::nivel).thenComparing(Chave::logger);
        Comparator<Chave> porLogger = Comparator.comparing(Chave::logger).thenComparing(Chave::nivel);

        assertThat(List.of(erroApi, infoDominio).stream().sorted(porNivel).toList())
                .containsExactly(infoDominio, erroApi);   // INFO vem antes de ERROR no enum
        assertThat(List.of(erroApi, infoDominio).stream().sorted(porLogger).toList())
                .containsExactly(erroApi, infoDominio);   // api.X antes de domain.Y

        // um Comparator<Object> também serve, e é por isso que o parâmetro é ? super K
        Comparator<Object> porTexto = Comparator.comparing(Object::toString);
        assertThat(List.of(erroApi, infoDominio).stream().sorted(porTexto).toList())
                .containsExactly(erroApi, infoDominio);
    }
}
