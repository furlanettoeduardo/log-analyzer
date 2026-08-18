# log-analyzer

Ferramenta de linha de comando (picocli) que lê arquivos de log e agrega métricas.

```bash
./mvnw compile
java -cp "target/classes;$(cat target/cp.txt)" \
     io.github.furlanettoeduardo.loganalyzer.LogAnalyzer summary app.log
```

Gerador de massa de teste: `node tools/gen-logs.js 500000 app.log`.

Comandos: `summary [--parallel]` e `top [--by logger|nivel|nivel-logger] [--limit N]`.

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
