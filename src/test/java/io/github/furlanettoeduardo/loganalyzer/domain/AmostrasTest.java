package io.github.furlanettoeduardo.loganalyzer.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AmostrasTest {

    private static List<Duration> ms(long... valores) {
        return java.util.Arrays.stream(valores).mapToObj(Duration::ofMillis).toList();
    }

    @Test
    void percentil_pelo_posto_mais_proximo() {
        // 1..100: ceil(0.95 × 100) - 1 = 94 -> o 95º valor, que é 95
        Estatisticas e = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> Duration.ofMillis(i))
                .collect(Amostras.coletor());

        assertThat(e.amostras()).isEqualTo(100);
        assertThat(e.min()).isEqualTo(Duration.ofMillis(1));
        assertThat(e.max()).isEqualTo(Duration.ofMillis(100));
        assertThat(e.media()).isEqualTo(Duration.ofMillis(50));   // 5050/100 = 50 (divisão inteira)
        assertThat(e.p95()).isEqualTo(Duration.ofMillis(95));
        assertThat(e.p99()).isEqualTo(Duration.ofMillis(99));
    }

    @Test
    void ordem_de_entrada_nao_importa() {
        Estatisticas desordenada = ms(300, 10, 50, 1000, 20).stream().collect(Amostras.coletor());
        Estatisticas ordenada = ms(10, 20, 50, 300, 1000).stream().collect(Amostras.coletor());

        assertThat(desordenada).isEqualTo(ordenada);
        assertThat(desordenada.min()).isEqualTo(Duration.ofMillis(10));
        assertThat(desordenada.max()).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void vazio_nao_estoura() {
        assertThat(Stream.<Duration>empty().collect(Amostras.coletor())).isEqualTo(Estatisticas.VAZIA);
    }

    @Test
    void cresce_alem_da_capacidade_inicial() {
        // capacidade inicial é 16; 1000 elementos exercitam o Arrays.copyOf
        Estatisticas e = IntStream.rangeClosed(1, 1000)
                .mapToObj(Duration::ofMillis)
                .collect(Amostras.coletor());

        assertThat(e.amostras()).isEqualTo(1000);
        assertThat(e.p99()).isEqualTo(Duration.ofMillis(990));
    }

    @Test
    void combiner_produz_o_mesmo_que_o_acumulo_sequencial() {
        Amostras esquerda = new Amostras();
        ms(10, 20, 30).forEach(esquerda::adicionar);
        Amostras direita = new Amostras();
        ms(40, 50).forEach(direita::adicionar);

        Estatisticas fundido = esquerda.fundir(direita).finalizar();
        Estatisticas sequencial = ms(10, 20, 30, 40, 50).stream().collect(Amostras.coletor());

        assertThat(fundido).isEqualTo(sequencial);
    }

    @Test
    void paralelo_usa_o_combiner_e_da_o_mesmo_resultado() {
        List<Duration> duracoes = IntStream.rangeClosed(1, 10_000)
                .mapToObj(i -> Duration.ofMillis((i * 7919L) % 10_000))   // embaralhado, determinístico
                .toList();

        Estatisticas sequencial = duracoes.stream().collect(Amostras.coletor());
        Estatisticas paralelo = duracoes.parallelStream().collect(Amostras.coletor());

        assertThat(paralelo).isEqualTo(sequencial);
    }

    @Test
    void caminho_ingenuo_e_collector_concordam() {
        List<Duration> duracoes = IntStream.rangeClosed(1, 5000)
                .mapToObj(i -> Duration.ofMillis((i * 104729L) % 7001))
                .toList();

        assertThat(duracoes.stream().collect(Amostras.coletor()))
                .isEqualTo(Estatisticas.deLista(duracoes));
    }
}
