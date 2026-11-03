// Prepare verified Termux package contents, previously extracted beneath cache/compiler.
// Usage: node package-compiler.mjs <cache> <project>
import fs from 'node:fs';
import path from 'node:path';
const [cache, project] = process.argv.slice(2);
const dest = path.join(project, 'feature/flash/src/main/jniLibs/arm64-v8a');
const assets = path.join(project, 'feature/flash/src/main/assets/toolchain');
fs.mkdirSync(dest, { recursive: true });
fs.mkdirSync(assets, { recursive: true });
const replacements = { 'libxml2.so.16': 'libxml2.so', 'libncursesw.so.6': 'libncursesw.so', 'libzstd.so.1': 'libzstd.so', 'libz.so.1': 'libz.so' };
function copyElf(from, name) {
    const buffer = fs.readFileSync(from);
    for (const [oldName, newName] of Object.entries(replacements)) {
        const needle = Buffer.from(oldName + '\0');
        let index = 0;
        while ((index = buffer.indexOf(needle, index)) >= 0) {
            buffer.fill(0, index, index + needle.length);
            buffer.write(newName, index);
            index += needle.length;
        }
    }
    fs.writeFileSync(path.join(dest, name), buffer);
}
const prefix = name => path.join(cache, 'compiler', name, 'data/data/com.termux/files/usr');
copyElf(path.join(prefix('clang'), 'bin/clang-21'), 'libclang-tool.so');
copyElf(path.join(prefix('lld'), 'bin/lld'), 'liblld-tool.so');
for (const name of ['clang', 'libllvm', 'libc++', 'libffi', 'libxml2', 'ncurses', 'zstd', 'libiconv', 'libandroid-glob', 'zlib']) {
    const dir = path.join(prefix(name), 'lib');
    for (const item of fs.readdirSync(dir)) {
        const file = path.join(dir, item);
        if (!fs.lstatSync(file).isFile() || fs.statSync(file).size < 100 || !item.includes('.so')) continue;
        if (name === 'clang' && item !== 'libclang-cpp.so') continue;
        if (name === 'libllvm' && item !== 'libLLVM.so') continue;
        const target = item.replace(/\.so(?:\.[0-9.]+)?$/, '.so');
        copyElf(file, target);
    }
}
fs.cpSync(path.join(prefix('clang'), 'lib/clang/21/include'), path.join(assets, 'clang/include'), { recursive: true, dereference: true });
const gccRoot = path.join(cache, 'gcc/xpack-arm-none-eabi-gcc-15.2.1-1.1');
fs.cpSync(path.join(gccRoot, 'arm-none-eabi/include'), path.join(assets, 'arm/include'), { recursive: true, dereference: true });
// Keep the complete multilib selection for Cortex-M, including hard/soft-float variants.
const variants = ['v6-m/nofp', 'v7-m/nofp', 'v7e-m/nofp', 'v7e-m+fp/softfp', 'v7e-m+fp/hard', 'v7e-m+dp/softfp', 'v7e-m+dp/hard', 'v8-m.base/nofp', 'v8-m.main/nofp', 'v8-m.main+fp/softfp', 'v8-m.main+fp/hard', 'v8-m.main+dp/softfp', 'v8-m.main+dp/hard', 'v8.1-m.main+mve/hard'];
for (const variant of variants) {
    const dir = path.join(assets, 'arm/lib', variant);
    fs.mkdirSync(dir, { recursive: true });
    for (const lib of ['libc.a', 'libm.a', 'libnosys.a', 'libstdc++.a', 'libsupc++.a']) {
        const file = path.join(gccRoot, 'arm-none-eabi/lib/thumb', variant, lib);
        if (fs.existsSync(file)) fs.copyFileSync(file, path.join(dir, lib));
    }
    fs.copyFileSync(path.join(gccRoot, 'lib/gcc/arm-none-eabi/15.2.1/thumb', variant, 'libgcc.a'), path.join(dir, 'libgcc.a'));
}
console.log('Packaged Clang 21.1.8, LLD 21.1.8, Cortex-M multilibs and headers.');
