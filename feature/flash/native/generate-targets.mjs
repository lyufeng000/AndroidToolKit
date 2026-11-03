import fs from 'node:fs';
import path from 'node:path';
const [scripts, output] = process.argv.slice(2);
const dir = path.join(scripts, 'target');
function contents(file, seen = new Set()) {
    if (seen.has(file) || !fs.existsSync(file)) return '';
    seen.add(file);
    const text = fs.readFileSync(file, 'utf8');
    return text + [...text.matchAll(/source\s+\[find\s+(target\/[^\]\s]+)\]/g)]
        .map(match => contents(path.join(scripts, match[1]), seen)).join('\n');
}
const lines = [];
for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.cfg')).sort()) {
    const id = file.slice(0, -4);
    if (/common|core[0-9]$/.test(id)) continue;
    const text = contents(path.join(dir, file));
    if (!/flash bank/.test(text)) continue;
    let wire = /swj_newdap|swj-dp|swd newdap|sw-dp/.test(text) ? 'SWD,JTAG' : 'JTAG';
    if (/^nrf|^rp2040|^atsamd|^atsaml|^stm32(f0|g0|l0|l5|u5|wb|wl)/.test(id)) wire = 'SWD';
    const label = /^stm32/.test(id) ? id.toUpperCase().replaceAll('X', 'x')
        : /^nrf/.test(id) ? 'Nordic ' + id.toUpperCase()
        : /^rp2040/.test(id) ? 'Raspberry Pi RP2040'
        : /^lpc|^kinetis/.test(id) ? 'NXP ' + id.toUpperCase()
        : /^atsam|^at91sam/.test(id) ? 'Microchip ' + id.toUpperCase()
        : /^efm32/.test(id) ? 'Silicon Labs EFM32'
        : /^gd32/.test(id) ? 'GigaDevice ' + id.toUpperCase() : id;
    const address = /^stm32|^gd32/.test(id) ? '0x08000000'
        : /^nrf|^lpc|^kinetis|^efm32/.test(id) ? '0x00000000'
        : id === 'rp2040' ? '0x10000000' : '';
    lines.push([id, label, wire, address].join('\t'));
}
fs.writeFileSync(output, lines.join('\n') + '\n');
console.log(`${lines.length} target configurations`);
