# log-analyzer

Ferramenta de linha de comando (picocli) que lê arquivos de log e agrega métricas.

```bash
./mvnw compile
java -cp "target/classes;$(cat target/cp.txt)" \
     io.github.furlanettoeduardo.loganalyzer.LogAnalyzer summary app.log
```

Gerador de massa de teste: `node tools/gen-logs.js 500000 app.log`.

Comandos: `summary [--parallel]`, `top [--by logger|nivel|nivel-logger] [--limit N]` e
`window [--size 30s|1m|5m|1h] [--limit N] [--naive] [--parallel]`.

Saída:

```
Total de linhas: 500000
TRACE: 0
DEBUG: 70767
INFO:  285103
WARN:  71231
ERROR: 71427
Malformadas: 1472
  NIVEL_DESCONHECIDO   1472
```

O detalhamento por motivo é informação sobre a qualidade do log, não só sobre a
ferramenta: as 1.472 linhas corrompidas do `app.log` caem em `NIVEL_DESCONHECIDO`, e não
em `ESTRUTURA_INVALIDA`, porque `### linha corrompida ###` tem os quatro campos
posicionais — o que falha é o nível.

## Nota de refatoração: o bug que os dados não mostravam

A primeira versão do `summary` classificava a linha com `linha.contains(" INFO ")`.
Isso confunde o campo com a mensagem: uma linha `ERROR` cujo `msg` contenha a palavra
`INFO` era contada como INFO.

O detalhe que vale registrar é que **rodar a ferramenta não revelou o bug**. Sobre as
500.000 linhas do `app.log`, as duas versões — `contains` e parser — produzem números
idênticos, porque o gerador escreve sempre `msg="op N"` e nenhuma mensagem contém
palavra de nível (`grep -c 'msg="[^"]*\(INFO\|WARN\|ERROR\|DEBUG\)' app.log` → 0).

O bug era invisível nos dados de teste e só apareceria em produção, no primeiro log com
"ERROR" na mensagem. Quem provou a correção foi o teste
`LogParserTest.deve_usar_o_nivel_do_campo_e_nao_a_mensagem`, não a execução.

A diferença só aparece com uma linha construída para o caso, adicionada ao
`src/test/resources/sample.log`:

```
2026-08-14T09:00:05.000Z ERROR com.acme.api.PagamentoAdapter traceId=deadbeef msg="request INFO header invalido"
```

| sample.log (31 linhas) | `contains` | parser |
|---|---|---|
| INFO | 12 | 11 |
| WARN | 6 | 6 |
| ERROR | 8 | 9 |
| DEBUG | 5 | 5 |

Consequência prática: o `gen-logs.js` é cego para essa classe de bug. Massa de teste
gerada só cobre o que o gerador sabe produzir.

## Exaustividade verificada em compilação

O parser não devolve `null`. Devolve `ParseResult`, uma `sealed interface` com exatamente
dois casos (`Ok` e `Malformed`), e quem consome desestrutura com record pattern:

```java
switch (parser.parse(linha)) {
    case ParseResult.Ok(LogEntry entry) -> contar(entry.nivel());
    case ParseResult.Malformed(String texto, Motivo motivo) -> registrarFalha(motivo);
}
```

Sem `default`, sem `instanceof`, sem cast. Experimento: adicionar um terceiro caso à
interface (`record Skipped(String motivo) implements ParseResult {}`) e compilar. O
compilador aponta os três lugares que precisam ser corrigidos:

```
SummaryCommand.java:49: error: the switch statement does not cover all possible input values
LogParserTest.java:77: error: the switch expression does not cover all possible input values
LogParserTest.java:85: error: the switch expression does not cover all possible input values
```

Com `null` no lugar do tipo selado, uma nova forma de falha não avisaria ninguém: o dado
sumiria em silêncio e o `NullPointerException` apareceria em runtime, na linha 40 mil de
um arquivo em produção.

Detalhe que vale saber (medido no javac 21.0.12): essa garantia vale para switch de
**expressão** e para switch com **pattern label** — como o de cima, sobre a interface
selada. Um switch *statement* clássico sobre enum (`case INFO -> info++;`) continua
dispensado de exaustividade por compatibilidade: apagar um `case` compila sem erro e sem
aviso, mesmo com `-Xlint:all -Werror`.

## Modelo de domínio

| tipo | papel |
|---|---|
| `Level` | enum dos níveis; `Level.parse` devolve `Optional<Level>` em vez de lançar |
| `LogEntry` | record com os campos da linha; `duracao` é `Optional<String>` |
| `ParseResult` | `sealed interface`: `Ok(LogEntry)` ou `Malformed(linha, Motivo)` |
| `LogParser` | `String` → `ParseResult`, sem `null` e sem exceção de controle de fluxo |

Os dois usos de ausência ficam separados de propósito: `Optional.empty()` no `duracao` é
"linha válida, campo não existia"; `Malformed` é "não deu para ler a linha", e o `Motivo`
diz qual dos quatro pontos falhou.

## Agregação: duas contagens num passo só

`collect` devolve uma coisa, e o `summary` precisa de duas (por nível e por motivo de
falha). A saída é `Collectors.teeing`, que alimenta dois collectors com o mesmo elemento
e funde os resultados — cada lado usando `flatMapping` com um switch exaustivo sobre o
`ParseResult` para ficar só com o caso que lhe interessa:

```java
Collectors.teeing(
    Collectors.flatMapping(SummaryCommand::apenasEntry,  groupingBy(LogEntry::nivel, ..., counting())),
    Collectors.flatMapping(SummaryCommand::apenasMotivo, groupingBy(identity(),      ..., counting())),
    Resumo::new)
```

Sem campo mutável em lugar nenhum — por isso `--parallel` dá exatamente os mesmos
números (500.000 linhas, 1.472 malformadas nos dois casos). É a diferença entre acumular
em estado compartilhado e acumular em acumuladores independentes que se fundem no fim.

## Dois experimentos que valem registro

**O contrato de hashCode.** `top --by nivel-logger` agrupa por `record Chave(Level, String)`.
Trocando o record por uma classe comum sem `equals`/`hashCode`, sobre o mesmo `app.log`:

```
linhas validas:               498528
grupos com record Chave:      16
grupos com classe sem equals: 498528      <- um grupo por objeto, todos de tamanho 1
```

`HashMap` procura o balde pelo `hashCode` e confirma com `equals`. Sem os dois, cada
instância nova é uma chave nova, e o `groupingBy` vira uma lista cara.

**Checked exception dentro de lambda.** `Function.apply` não declara `throws`, então IO
dentro de `.map()` não compila:

```
error: unreported exception IOException; must be caught or declared to be thrown
        .map(p -> Files.readString(p))
```

É por isso que existe `UncheckedIOException` — e é dele que este projeto depende sem
perceber: `Files.lines` declara `IOException` na abertura (tratada no `call()`), mas uma
falha de leitura no meio do stream chega como `UncheckedIOException`.

## O comando window e o Collector customizado

```
$ loganalyzer window app.log --size 1m --limit 5
janelas de 1m: 830  (mostrando 5, Collector customizado)
janela                       linhas   amostras       min     média       p95       p99       max
2026-08-14T09:00:00Z            592        510       6ms     337ms    1753ms    2660ms    2882ms
2026-08-14T09:01:00Z            578        497       6ms     357ms    1779ms    2521ms    2857ms
```

`linhas` conta tudo que caiu na janela; `amostras` só o que tinha `duration_ms`. As duas
informações saem do mesmo passo via `teeing`, e `Optional.stream()` descarta as linhas
sem duração sem filtro solto.

### Por que combiner() existe se o stream é sequencial?

Porque `Collector` é um contrato, não uma implementação para o seu caso. Em stream
sequencial o `combiner` **nunca é chamado**. Verificado trocando o combiner por um que
sempre lança, sobre 200.000 amostras:

```
sequencial: PT3M18S                          <- passou sem tocar no combiner
paralelo:   AssertionError: combiner chamado!
```

(de quebra, o `PT3M18S` é o `Duration.toString()` em ISO-8601 que o comando formata
como ms na saída.) Ele existe porque é o que torna o collector
utilizável em paralelo: cada thread acumula no seu próprio `Amostras`, e no fim as partes
se fundem duas a duas. Sem combiner não há como dividir o trabalho, e é exatamente a
diferença entre acumular em estado compartilhado (que quebrou o `total++` paralelo) e
acumular em partes independentes que se juntam no fim.

`AmostrasTest.paralelo_usa_o_combiner_e_da_o_mesmo_resultado` cobre isso com 10.000
amostras embaralhadas: `parallelStream()` e `stream()` produzem o mesmo record.

### Percentil exato ou aproximado?

Escolhi **exato**, guardando todas as amostras num `long[]` primitivo que cresce por
duplicação. O raciocínio é sobre o `n` que importa: percentil exato precisa das amostras
todas, mas aqui elas são por janela — uma janela de 1m tem ~500 amostras, não 500 mil.
Memória O(n) por janela, com n pequeno e limitado pelo tamanho da janela, não pelo
tamanho do arquivo. E `long[]` evita o boxing que uma `List<Long>` traria.

Histograma de buckets ou t-digest trocam exatidão por memória constante, e valem quando
a janela é ilimitada (um stream que nunca fecha) ou quando há milhões de séries
simultâneas — que é o caso do Prometheus, e por isso o histograma dele dá p99
aproximado, dependente da configuração dos buckets.

### O que a medição mostrou (e desmentiu)

Sobre o `app.log` de 500 mil linhas, comparando os dois caminhos:

```
pipeline inteiro (parse + agregação)     tempo      alocado
  ingenuo  (List<Duration>)             1676 ms    1975,3 MB
  collector (long[])                    1626 ms    1974,0 MB
```

Empate — porque o parsing domina: quase 2 GB de alocação são strings, matchers e
objetos de domínio. **Otimizar a agregação aqui não mudaria nada**, e a medição é o que
mostra isso.

Isolando só a fase de agregação, sobre as 498.528 entradas já parseadas:

```
só a agregação                           tempo      alocado
  ingenuo  (List<Duration>)               87 ms      69,4 MB
  collector (long[])                      72 ms      68,0 MB
  collector + filter/mapping              71 ms      28,9 MB
  ingenuo  + filter/mapping               106 ms     30,2 MB
```

O collector é ~1,5× mais rápido que o caminho ingênuo (72 vs 106 ms com o mesmo filtro),
porque `Arrays.sort(long[])` não paga boxing nem indireção.

A surpresa está na terceira linha: trocar `flatMapping(e -> e.duracao().stream(), ...)`
por `filter` + `mapping` derrubou a alocação de 68 MB para 28,9 MB. O custo não estava
onde eu tinha anotado — cada `Optional.stream()` aloca um `Stream` por elemento, e eram
~39 MB só disso. O código mantém o `flatMapping` porque expressa melhor a intenção e a
diferença é irrelevante no pipeline completo; a nota fica registrada para o Bloco 13.

### Janelas de tamanho arbitrário

`instant.truncatedTo(unidade)` não serve: exige unidade que divida o dia sem resto.

```
UnsupportedTemporalTypeException: Unit must divide into a standard day without remainder
```

A saída é aritmética sobre o epoch — `Math.floorDiv(epochMs, tamanhoMs) * tamanhoMs` —
que aceita 30s, 7min, o que for. Consequência a entender: as janelas passam a ser
ancoradas no epoch, não no dia. Com `--size 7m`, `09:07:33Z` cai na janela `09:01:00Z`,
porque 29.778.301 minutos desde 1970 é múltiplo de 7 — e é justamente essa
não-coincidência com o início do dia que o `truncatedTo` se recusa a produzir.
