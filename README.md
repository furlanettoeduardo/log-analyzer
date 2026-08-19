# Log Analyzer — Bloco 0

CLI de análise de log em Java 21 puro, sem framework. Repo de aprendizado da trilha
Node.js → Java: existe para exercitar a linguagem antes que o Spring esconda tudo atrás
de anotação.

**Não é código de produção.** É registro de experimento. Cada decisão abaixo tem uma
pergunta, um número medido e uma conclusão — inclusive as conclusões que contrariaram o
que eu tinha escrito antes de medir.

## O que a ferramenta faz

```
loganalyzer summary app.log [--parallel]
loganalyzer top     app.log --by logger|nivel|nivel-logger [--limit N]
loganalyzer window  app.log --size 30s|1m|5m|1h [--limit N] [--naive] [--parallel]
```

Formato de log esperado:

```
2026-08-14T09:12:33.482Z INFO  com.acme.api.ReservaController  traceId=7f3a2b91 msg="reserva criada" duration_ms=142
```

Timestamp ISO-8601, nível, logger, e três campos `chave=valor` no resto — `duration_ms`
falta em ~15% das linhas, de propósito. Massa de teste: `node tools/gen-logs.js 500000 app.log`
(o gerador injeta ~0,3% de linhas corrompidas).

Rodar sem instalar nada:

```powershell
./mvnw compile
./mvnw dependency:build-classpath -Dmdep.outputFile=target/cp.txt
.\loganalyzer.ps1 summary app.log
```

Saída, sobre 500.000 linhas:

```
Total de linhas: 500000
TRACE: 0
DEBUG: 70767
INFO:  285103
WARN:  71231
ERROR: 71427
Malformadas: 1472
  TIMESTAMP_INVALIDO   1472
```

```
$ loganalyzer window app.log --size 1m --limit 3
janelas de 1m: 830  (mostrando 3, Collector customizado)
janela                       linhas   amostras       min     média       p95       p99       max
2026-08-14T09:00:00Z            592        510       6ms     337ms    1753ms    2660ms    2882ms
2026-08-14T09:01:00Z            578        497       6ms     357ms    1779ms    2521ms    2857ms
2026-08-14T09:02:00Z            587        513       6ms     417ms    2220ms    2688ms    2962ms
```

`linhas` conta tudo que caiu na janela; `amostras` só o que tinha `duration_ms`. As duas
saem do mesmo passo, via `Collectors.teeing`.

## Modelo de domínio

| tipo | papel |
|---|---|
| `Level` | enum dos níveis; `Level.parse` devolve `Optional<Level>` em vez de lançar |
| `LogEntry` | record: `Instant`, `Level`, logger, traceId, mensagem, `Optional<Duration>` |
| `ParseResult` | `sealed interface`: `Ok(LogEntry)` ou `Malformed(linha, Motivo)` |
| `LogParser` | `String` → `ParseResult`, sem `null` e sem exceção como controle de fluxo |
| `Chave` | record `(Level, String)` para agrupamento composto |
| `Amostras` | acumulador em `long[]` do `Collector` customizado de latência |
| `Estatisticas` | record com count, min, max, média, p95, p99 |

---

# Decisões de design

## 1. `sealed interface ParseResult` no lugar de `null`

O parser tinha quatro `return null` que significavam coisas diferentes: estrutura
inválida, timestamp inválido, nível desconhecido, campo ausente. Quem chamava recebia o
mesmo `null` nos quatro casos e jogava fora a informação. Pior: nada obrigava a testar o
`null` — esquecer compilava e dava `NullPointerException` em produção.

Hoje o parser devolve `Ok(LogEntry)` ou `Malformed(linha, Motivo)`, e quem consome
desestrutura com record pattern:

```java
switch (parser.parse(linha)) {
    case ParseResult.Ok(LogEntry entry)                      -> contar(entry.nivel());
    case ParseResult.Malformed(String texto, Motivo motivo)  -> registrarFalha(motivo);
}
```

Sem `default`, sem `instanceof`, sem cast. O custo: um objeto a mais por linha, e a
obrigação de tratar todos os casos — que é justamente o ponto.

## 2. `Optional<Level>` no enum, exceção no `CriterioConverter`

A mesma operação — texto vira enum — tem contrato diferente em cada lugar.

`Level.parse("FATAL")` devolve `Optional.empty()`: log com nível desconhecido é **caso
esperado**, acontece o tempo todo, e a ferramenta tem que continuar contando.

`CriterioConverter.convert("loggr")` lança `IllegalArgumentException`: argumento de CLI
errado é **erro do usuário**, e o Picocli precisa da exceção para imprimir o usage e
sair com código não-zero.

A assimetria é deliberada. Devolver `Optional` no converter esconderia um erro de
digitação; lançar no parser derrubaria a ferramenta por causa de uma linha ruim.

## 3. `Comparator<? super K>` no lugar de `Comparable` no `Chave`

Primeira tentativa: `Chave implements Comparable<Chave>`, para o desempate genérico
funcionar. Está errado, e o motivo é conceitual. Igualdade tem definição natural — todos
os campos iguais, e é por isso que o record dá `equals`/`hashCode` de graça. Ordenação
não tem: `Chave(ERROR, "com.acme.A")` vem antes ou depois de `Chave(INFO, "com.acme.B")`?
Depende de quem pergunta.

Então a ordem vem de fora:

```java
private <K> void imprimir(Map<K, Long> contagem, Comparator<? super K> desempate)
```

`? super K` e não `Comparator<K>` porque um comparador de supertipo serve: um
`Comparator<Object>` que compara por `toString` ordena `Chave` perfeitamente. Comparator
**consome** K, e o que consome aceita supertipo — o "super" do PECS. `ChaveTest` tem as
três ordens convivendo, incluindo a `Comparator<Object>`.

## 4. Percentil exato, com `long[]`

Percentil exato exige guardar todas as amostras — não existe p99 exato a partir de
contadores. A pergunta certa é qual `n` importa: aqui as amostras são **por janela**
(~500 numa janela de 1m), não por arquivo. Memória O(n) por janela, com n limitado pelo
tamanho da janela e não pelo tamanho do log. `long[]` em vez de `List<Long>` elimina o
boxing.

Histograma de buckets ou t-digest trocam exatidão por memória constante, e valem quando
a janela é ilimitada (stream que nunca fecha) ou quando há milhões de séries simultâneas.
É o caso do Prometheus — e é por isso que o p99 dele é aproximado e depende de como os
buckets foram configurados.

Método do percentil: posto mais próximo, `ceil(p × n) - 1` sobre o vetor ordenado, sem
interpolação. O valor devolvido é sempre uma amostra real, o que importa quando alguém
pergunta "qual requisição foi essa?".

## 5. Fail-fast no `Motivo`

O parser reporta **a primeira validação que falhou**, não todas as violações da linha.
Uma linha sem timestamp e sem traceId sai como `TIMESTAMP_INVALIDO`, só.

A evidência apareceu sozinha: quando tipei `timestamp` como `Instant`, a validação de
timestamp passou a rodar antes da de nível, e as 1.472 linhas corrompidas do `app.log`
migraram de `NIVEL_DESCONHECIDO` para `TIMESTAMP_INVALIDO`. As linhas não mudaram — a
ordem das validações mudou.

A alternativa é acumular todas as violações numa lista, que é o que Bean Validation faz.
Para diagnóstico de log em massa, fail-fast basta e é mais barato; para validar payload
de request, acumular é melhor, porque o usuário quer ver todos os erros de uma vez.

---

# Experimentos

## 1. `readAllLines` vs `Files.lines`, com heap apertado

**Pergunta:** ler o arquivo inteiro na memória é mesmo problema, ou é folclore?

```
$ java -Xmx64m MemoriaIO.java app.log        # app.log tem 54 MB
heap maximo: 64 MB
Files.lines     -> 71427 linhas ERROR em 182 ms, pico de heap 36 MB
readAllLines    -> OutOfMemoryError: Java heap space
```

**Conclusão:** `Files.lines` é lazy — processa e descarta, e o heap fica constante
independente do tamanho do arquivo. `readAllLines` materializa uma `List<String>` com
500 mil strings e morre. A ferramenta tem que funcionar em arquivo maior que a RAM.

## 2. Contador mutável vs `teeing`, em paralelo

**Pergunta:** o que exatamente acontece quando eu paralelizo um `forEach` que incrementa
campo?

```
$ java CorridaContador.java app.log
sequencial : total=500000  soma dos baldes=500000
paralelo 1 : total=485722  soma dos baldes=492211  (perdeu 14278 linhas)
paralelo 2 : total=481949  soma dos baldes=486771  (perdeu 18051 linhas)
paralelo 3 : total=487095  soma dos baldes=490752  (perdeu 12905 linhas)
```

Três execuções, três resultados diferentes, e nenhum bate. Repare que `total` e a soma
dos baldes divergem entre si: cada campo perde incrementos por conta própria, porque
`total++` são três operações (ler, somar, escrever) e duas threads leem o mesmo valor
antes de qualquer uma escrever.

A mesma carga com o `collect(teeing(...))` de hoje:

```
$ loganalyzer summary app.log --parallel     # três execuções seguidas
Total de linhas: 500000   INFO 285103   WARN 71231   ERROR 71427   DEBUG 70767   malf 1472
Total de linhas: 500000   INFO 285103   WARN 71231   ERROR 71427   DEBUG 70767   malf 1472
Total de linhas: 500000   INFO 285103   WARN 71231   ERROR 71427   DEBUG 70767   malf 1472
```

**Conclusão:** mesmo paralelismo, mesma máquina. A diferença é que o collector não
compartilha estado mutável — cada thread acumula no seu próprio acumulador e as partes se
fundem no fim, pelo `combiner`. É a resposta que eu daria para "como você lida com
concorrência em Java?", e é melhor que citar `synchronized`, que resolveria a corretude
matando o paralelismo.

Virou teste: `SummaryCommandTest.paralelo_produz_exatamente_o_mesmo_resumo`.

## 3. `record Chave` vs classe comum sem `equals`/`hashCode`

**Pergunta:** o que o `groupingBy` faz quando a chave não cumpre o contrato?

```
$ java ChaveExperimento.java app.log
linhas validas:               498528
grupos com record Chave:      16          maior grupo: 71383
grupos com classe sem equals: 498528      maior grupo: 1

record:      a.equals(b)=true   hash iguais=true
classe crua: c.equals(d)=false  hash iguais=false
```

**Conclusão:** o `HashMap` chamou `hashCode()`, cada objeto devolveu seu endereço, e
nenhuma busca encontrou nada. Além de errado, o efeito colateral que ninguém menciona: o
mapa vira uma lista disfarçada, com uma entrada por objeto e memória O(n). É o mesmo bug
da entidade JPA em `HashSet`, sem o Hibernate no meio para confundir.

## 4. `combiner()` que lança

**Pergunta:** o `combiner` é chamado num stream sequencial?

```
$ java CombinerNunca.java        # combiner substituído por um que sempre lança
sequencial: PT3M18S
paralelo:   AssertionError: combiner chamado!
```

**Conclusão:** não. Prova por contradição, com 200.000 amostras. O `combiner` existe
porque `Collector` é um **contrato**, e o contrato não sabe se vai ser usado em paralelo
— quem decide é quem chama `collect`. Sem combiner não haveria como dividir o trabalho.

(De quebra, o `PT3M18S` é o `Duration.toString()` em ISO-8601. A saída do comando formata
como `198000ms`, porque log não se lê em ISO de duração.)

## 5. Checked exception dentro de lambda

**Pergunta:** por que IO dentro de `.map()` não compila, mesmo com o método declarando
`throws`?

```
$ javac LambdaChecked.java
LambdaChecked.java:10: error: unreported exception IOException; must be caught or declared to be thrown
                .map(p -> Files.readString(p))
```

O mesmo `Files.readString` num `for`, no método ao lado, com o mesmo `throws IOException`
na assinatura, compila sem reclamar.

**Conclusão:** `Function.apply` não declara `throws`, e o `throws` do método envolvente
não alcança o corpo da lambda — quem executa `apply` é o `Stream`, não o meu método. É
por isso que existe `UncheckedIOException`, e é dele que este projeto depende sem ter
percebido: `Files.lines` declara `IOException` só na abertura, mas falha de leitura no
meio do stream chega envelopada.

## 6. Exaustividade: o terceiro caso na interface selada

**Pergunta:** o compilador realmente me obriga a tratar um caso novo?

Adicionando `record Skipped(String motivo) implements ParseResult {}`:

```
SummaryCommand.java:49: error: the switch statement does not cover all possible input values
LogParserTest.java:77:  error: the switch expression does not cover all possible input values
LogParserTest.java:85:  error: the switch expression does not cover all possible input values
```

Três sites, apontados um a um. Com `null` no lugar do tipo selado, uma nova forma de
falha não avisaria ninguém.

**Ressalva medida no javac 21.0.12:** essa garantia vale para switch de **expressão** e
para switch com **pattern label**. Um switch *statement* clássico sobre enum
(`case INFO -> info++;`) continua dispensado de exaustividade por compatibilidade —
apagar um `case` compila sem erro e **sem aviso**, mesmo com `-Xlint:all -Werror`
(confirmei que o lint estava ativo compilando um `List` raw ao lado: o `[rawtypes]`
apareceu).

## 7. O bug que os dados não mostravam

**Pergunta:** a primeira versão classificava com `linha.contains(" INFO ")`. Isso é
problema real ou teórico?

Sobre o `app.log` gerado, as duas versões dão números **idênticos**:

```
$ grep -c 'msg="[^"]*\(INFO\|WARN\|ERROR\|DEBUG\)' app.log
0
```

Nenhuma das 500.000 mensagens contém palavra de nível, porque o gerador escreve sempre
`msg="op N"`. Com uma linha construída para o caso, adicionada ao `sample.log`:

```
2026-08-14T09:00:05.000Z ERROR com.acme.api.PagamentoAdapter traceId=deadbeef msg="request INFO header invalido"
```

| sample.log (31 linhas) | `contains` | parser |
|---|---|---|
| INFO | 12 | 11 |
| ERROR | 8 | 9 |

E num corpus adversarial (cópia do `app.log` com 1 linha em 100 tendo `INFO` na
mensagem): o balde INFO inflava em 2.122 linhas, roubadas exatamente de WARN (686),
ERROR (735) e DEBUG (701).

**Conclusão:** o bug era invisível nos dados de teste e só apareceria em produção, no
primeiro log com "ERROR" na mensagem. **Quem provou a correção foi o teste, não a
execução** — e a massa gerada é cega para essa classe de bug, porque só cobre o que o
gerador sabe produzir.

---

# Medições de performance

## A anotação que a medição desmentiu

**O que eu tinha escrito no código**, como nota para o futuro: o `flatMapping` com
`Stream.of`/`Stream.empty()` aloca dois streams efêmeros por linha, ~1 milhão de objetos
em 500 mil linhas — candidato a otimização.

**O que a medição mostrou.** Isolando a fase de agregação sobre as 498.528 entradas já
parseadas:

```
só a agregação                           tempo      alocado
  ingenuo  (List<Duration>)               87 ms      69,4 MB
  collector (long[])                      72 ms      68,0 MB
  collector + filter/mapping              71 ms      28,9 MB
  ingenuo  + filter/mapping              106 ms      30,2 MB
```

A terceira linha é a que desmente: trocar `flatMapping(e -> e.duracao().stream(), …)` por
`filter` + `mapping` derrubou a alocação de 68 MB para 28,9 MB. O custo não estava no
`Stream.of` do collector — estava no `Optional.stream()`, que aloca um `Stream` por
elemento. **39 dos 68 MB**, num lugar que eu não tinha suspeitado.

**O que eu decidi: não otimizar.** Porque no pipeline completo isso é ruído:

```
pipeline inteiro (parse + agregação)     tempo      alocado
  ingenuo  (List<Duration>)             1676 ms    1975,3 MB
  collector (long[])                    1626 ms    1974,0 MB
```

Quase 2 GB de alocação, dominados pelo parsing — strings, matchers, objetos de domínio.
Os 39 MB do `Optional.stream()` são 2% disso. Mantive o `flatMapping` porque expressa
melhor a intenção (o `Optional` some sem `isPresent()`/`get()` espalhado) e a diferença
não aparece no relógio.

A conclusão do experimento foi "deixa como está" — e chegar nela medindo, depois de ter
escrito a hipótese errada no próprio código, é o ponto.

## O que o collector de fato ganha

Com o mesmo filtro nos dois lados: **72 ms contra 106 ms**, ~1,5× mais rápido.
`Arrays.sort(long[])` usa dual-pivot quicksort sobre memória contígua, sem boxing e sem
indireção; ordenar `List<Long>` paga ponteiro por elemento e comparação virtual.

Metodologia que valeu mais que o número: medir o todo primeiro, descobrir que o gargalo
não estava onde eu ia mexer, e só então isolar o componente para medi-lo sozinho.

---

# Três perguntas do critério

## Por que `record` não substitui classe em todo caso

`record` é um bom default para dado imutável de transporte, e este projeto usa cinco. Mas
ele fecha portas de propósito:

- **Mutabilidade.** Os campos são `final` e o record é `final`. Se o objeto precisa mudar
  de estado ao longo da vida — uma conexão, um builder, um cache — record não serve.
- **Herança.** Record não estende classe alguma (já estende `java.lang.Record`). Serve
  para implementar interface, não para participar de hierarquia. Aqui isso virou vantagem:
  `Ok` e `Malformed` implementam a sealed interface e o compilador fecha o conjunto.
- **Encapsulamento da representação.** Os componentes **são** a API pública: `Chave(Level,
  String)` expõe que a chave é um par. Trocar a representação interna quebra todo mundo
  que chama `chave.nivel()`. Uma classe comum pode guardar um `long` empacotado e expor
  o que quiser.
- **Validação.** Dá para validar no construtor compacto, mas o estado tem que estar
  válido no momento da construção — não existe "criar agora e completar depois".
- **Campos derivados.** Não existem: qualquer valor calculado é método, recomputado a cada
  chamada, ou exige cache manual (que esbarra na imutabilidade dos campos).

E o que ele dá de graça vale o preço quando o dado é dado: `Estatisticas` tem seis
componentes e o `equals` correto sai sozinho — é ele que faz
`assertThat(paralelo).isEqualTo(sequencial)` comparar mapas inteiros numa linha.

## Por que `Files.lines` precisa de try-with-resources e `readAllLines` não

`readAllLines` abre o arquivo, lê tudo, fecha e devolve uma `List` — quando o método
retorna, não há nada aberto. `Files.lines` devolve um `Stream` **preguiçoso** que segura
o file handle aberto enquanto não for consumido e fechado; por isso `Stream` implementa
`AutoCloseable` e o javadoc manda usar try-with-resources.

O sintoma do vazamento é cruel: cada chamada esquecida deixa um descritor pendurado, o
processo vai acumulando, e horas depois aparece `Too many open files` numa parte do
sistema que não tem nada a ver com a causa. É o tipo de bug que não reproduz em
desenvolvimento porque ninguém roda o loop 10 mil vezes.

O trade-off é o do experimento 1: `readAllLines` é mais simples e não vaza nada, mas
carrega o arquivo inteiro na memória.

## Por que `combiner()` existe num stream sequencial

Porque `Collector` é um contrato de quatro peças — `supplier`, `accumulator`, `combiner`,
`finisher` — e quem escreve o collector não sabe se quem chama vai usar `stream()` ou
`parallelStream()`. A prova de que não é chamado no caminho sequencial está no
experimento 4: um combiner que sempre lança, e o sequencial passa.

O combiner é o que torna a agregação **divisível**: cada thread acumula num `Amostras`
próprio e as partes se fundem duas a duas na volta. É exatamente o que falta ao
`total++`, e a diferença entre os dois é o experimento 2 inteiro.

---

# O que eu faria diferente

- **A ordem das validações no parser é acidental**, não projetada. Hoje o timestamp é
  validado antes do nível porque foi assim que o código cresceu, e isso decide qual
  `Motivo` aparece no relatório. Deveria ser uma decisão explícita — ou fail-fast com
  ordem documentada, ou acumular todas as violações.
- **`Estatisticas.media` é divisão inteira de millis.** Perde precisão sub-milissegundo e
  arredonda para baixo. Para latência de log dá, para SLA não daria.
- **O `top` ordena os grupos com `sorted().limit(n)`.** Aqui é irrelevante (4 ou 16
  grupos), mas com chave de alta cardinalidade — agrupar por `traceId` daria ~500 mil
  grupos — o certo seria um min-heap limitado a k, O(n log k) em vez de O(n log n), sem
  a barreira que o `sorted` impõe ao stream.
- **Não escrevi teste de CLI de ponta a ponta.** Os testes cobrem parser, agregação e
  collector; a camada Picocli (conversores, defaults, códigos de saída) está coberta só
  por execução manual.
- **`loganalyzer.ps1` é wrapper de classpath**, não empacotamento. O certo seria um jar
  autocontido via shade plugin, com `java -jar`.

---

# Números de referência

| | |
|---|---|
| linhas do `app.log` | 500.000 (54 MB) |
| linhas válidas | 498.528 |
| malformadas | 1.472 (todas `TIMESTAMP_INVALIDO`) |
| janelas de 1m | 830 |
| testes | 36, em 7 classes |
| tempo do `summary` | ~1,5 s sequencial, ~1,0 s com `--parallel` |
| heap mínimo para rodar | 64 MB (com `Files.lines`; `readAllLines` estoura) |
