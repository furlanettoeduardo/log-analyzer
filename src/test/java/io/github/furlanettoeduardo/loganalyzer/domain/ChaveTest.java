package io.github.furlanettoeduardo.loganalyzer.domain;

import org.junit.jupiter.api.Test;

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
    void tem_ordem_natural_para_desempate_generico() {
        // record não implica Comparable; a ordem foi declarada de propósito para o
        // bound <K extends Comparable<? super K>> do TopCommand.imprimir aceitar Chave
        Chave erroApi = new Chave(Level.ERROR, "com.acme.api.X");
        Chave erroDominio = new Chave(Level.ERROR, "com.acme.domain.Y");
        Chave infoDominio = new Chave(Level.INFO, "com.acme.domain.Y");

        assertThat(erroApi).isLessThan(erroDominio);      // mesmo nível, desempata por logger
        assertThat(infoDominio).isLessThan(erroApi);      // INFO vem antes de ERROR no enum
        assertThat(List.of(erroDominio, erroApi, infoDominio).stream().sorted().toList())
                .containsExactly(infoDominio, erroApi, erroDominio);
    }

    @Test
    void hashCode_e_consistente_com_equals() {
        Chave a = new Chave(Level.WARN, "com.acme.domain.ReservaService");
        Chave b = new Chave(Level.WARN, "com.acme.domain.ReservaService");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).hasToString("WARN com.acme.domain.ReservaService");
    }
}
