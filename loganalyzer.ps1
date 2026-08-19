# Wrapper para rodar a CLI sem digitar o classpath.
#   .\loganalyzer.ps1 summary app.log
#   .\loganalyzer.ps1 top app.log --by nivel-logger --limit 5

$raiz     = $PSScriptRoot
$classes  = Join-Path $raiz 'target\classes'
$cpFile   = Join-Path $raiz 'target\cp.txt'
$mvnw     = Join-Path $raiz 'mvnw.cmd'

if (-not (Test-Path $classes)) {
    Write-Host 'compilando...' -ForegroundColor DarkGray
    & $mvnw -q compile
}

if (-not (Test-Path $cpFile)) {
    Write-Host 'resolvendo classpath...' -ForegroundColor DarkGray
    & $mvnw -q dependency:build-classpath '-Dmdep.outputFile=target/cp.txt'
}

java -cp "$classes;$(Get-Content $cpFile)" io.github.furlanettoeduardo.loganalyzer.LogAnalyzer @args
