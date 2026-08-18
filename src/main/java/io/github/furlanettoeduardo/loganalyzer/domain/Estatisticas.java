package io.github.furlanettoeduardo.loganalyzer.domain;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Estatísticas de latência de uma janela. Percentil pelo método do posto mais próximo
 * (nearest-rank): índice = ceil(p × n) - 1 sobre o vetor ordenado. É a definição do
 * NIST, sem interpolação — o valor devolvido é sempre uma amostra real, o que importa
 * quando alguém pergunta "qual requisição foi essa".
 */
public record Estatisticas(
        long amostras,
        Duration min,
        Duration max,
        Duration media,
        Duration p95,
        Duration p99
) {

    public static final Estatisticas VAZIA =
            new Estatisticas(0, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);

    /** Caminho ingênuo: precisa da lista inteira em memória, com boxing. */
    public static Estatisticas deLista(Collection<Duration> duracoes) {
        if (duracoes.isEmpty()) {
            return VAZIA;
        }
        List<Long> ordenadas = duracoes.stream().map(Duration::toMillis).sorted().toList();
        long soma = ordenadas.stream().mapToLong(Long::longValue).sum();
        int n = ordenadas.size();
        return new Estatisticas(
                n,
                Duration.ofMillis(ordenadas.get(0)),
                Duration.ofMillis(ordenadas.get(n - 1)),
                Duration.ofMillis(soma / n),
                Duration.ofMillis(ordenadas.get(indiceDoPercentil(0.95, n))),
                Duration.ofMillis(ordenadas.get(indiceDoPercentil(0.99, n))));
    }

    /** ceil(p × n) - 1, preso ao intervalo [0, n-1]. */
    public static int indiceDoPercentil(double percentil, int n) {
        int indice = (int) Math.ceil(percentil * n) - 1;
        return Math.max(0, Math.min(n - 1, indice));
    }
}
