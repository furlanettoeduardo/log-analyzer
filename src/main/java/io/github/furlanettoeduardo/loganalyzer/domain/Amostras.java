package io.github.furlanettoeduardo.loganalyzer.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collector;

/**
 * Acumulador mutável do Collector customizado: guarda as durações em long[] primitivo,
 * sem boxing e sem nós de lista.
 *
 * <p>Percentil exato exige as amostras todas — não existe p99 exato a partir de contadores.
 * A escolha aqui é exatidão com memória O(n) por janela, porque n por janela é pequeno
 * (uma janela de 1m tem centenas de linhas, não milhões). Histograma de buckets ou
 * t-digest trocariam exatidão por memória constante, e é o que fazem Prometheus e afins
 * quando as janelas são ilimitadas.
 */
public final class Amostras {

    private long[] valores = new long[16];
    private int tamanho;

    /** supplier, accumulator, combiner, finisher — as quatro peças de um Collector. */
    public static Collector<Duration, Amostras, Estatisticas> coletor() {
        return Collector.of(
                Amostras::new,
                Amostras::adicionar,
                Amostras::fundir,
                Amostras::finalizar);
    }

    public void adicionar(Duration duracao) {
        if (tamanho == valores.length) {
            valores = Arrays.copyOf(valores, valores.length * 2);
        }
        valores[tamanho++] = duracao.toMillis();
    }

    /**
     * O combiner: funde dois acumuladores parciais num só. Em stream sequencial nunca é
     * chamado — existe porque é ele que torna o Collector utilizável em paralelo, onde
     * cada thread acumula no seu próprio Amostras e as partes se juntam no fim.
     */
    public Amostras fundir(Amostras outra) {
        if (outra.tamanho > 0) {
            if (tamanho + outra.tamanho > valores.length) {
                valores = Arrays.copyOf(valores, Math.max(valores.length * 2, tamanho + outra.tamanho));
            }
            System.arraycopy(outra.valores, 0, valores, tamanho, outra.tamanho);
            tamanho += outra.tamanho;
        }
        return this;
    }

    public Estatisticas finalizar() {
        if (tamanho == 0) {
            return Estatisticas.VAZIA;
        }
        long[] ordenadas = Arrays.copyOf(valores, tamanho);
        Arrays.sort(ordenadas);

        long soma = 0;
        for (long valor : ordenadas) {
            soma += valor;
        }

        return new Estatisticas(
                tamanho,
                Duration.ofMillis(ordenadas[0]),
                Duration.ofMillis(ordenadas[tamanho - 1]),
                Duration.ofMillis(soma / tamanho),
                Duration.ofMillis(ordenadas[Estatisticas.indiceDoPercentil(0.95, tamanho)]),
                Duration.ofMillis(ordenadas[Estatisticas.indiceDoPercentil(0.99, tamanho)]));
    }

    public int tamanho() {
        return tamanho;
    }
}
