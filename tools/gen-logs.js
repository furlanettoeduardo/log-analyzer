const fs = require('fs');

const n = +process.argv[2] || 10000;
const out = process.argv[3] || 'app.log';

const levels = ['INFO','INFO','INFO','INFO','WARN','ERROR','DEBUG'];
const loggers = ['com.acme.api.ReservaController','com.acme.api.PagamentoAdapter',
    'com.acme.domain.ReservaService','com.acme.infra.JpaReservaRepository'];

const linhas = [];
let t = Date.parse('2026-08-14T09:00:00Z');

for (let i = 0; i < n; i++) {
    t += Math.floor(Math.random() * 200);
    if (Math.random() < 0.003) { linhas.push('### linha corrompida ###'); continue; }
    const lv = levels[Math.floor(Math.random() * levels.length)];
    const lg = loggers[Math.floor(Math.random() * loggers.length)];
    const tr = Math.random().toString(16).slice(2, 10);
    const dur = Math.random() < 0.85
        ? ` duration_ms=${Math.floor(Math.exp(Math.random() * 8) + 5)}` : '';
    linhas.push(`${new Date(t).toISOString()} ${lv.padEnd(5)} ${lg.padEnd(35)} traceId=${tr} msg="op ${i}"${dur}`);
}

fs.writeFileSync(out, linhas.join('\n') + '\n', { encoding: 'utf8' });
console.log(`${n} linhas escritas em ${out}`);