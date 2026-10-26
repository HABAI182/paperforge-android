import * as esbuild from 'esbuild';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = path.join(root, 'vendor/ofdjs/src');
function replaceOnce(text, before, after) {
  if (!text.includes(before)) throw new Error(`Upstream patch anchor missing: ${before}`);
  return text.replace(before, after);
}
const fonts = `
                const file = font['ofd:FontFile'];
                if (file) {
                    const clean = value => {
                        const out = [];
                        for (const part of value.replaceAll('\\\\','/').split('/')) {
                            if (!part || part === '.') continue;
                            if (part === '..') out.pop(); else out.push(part);
                        }
                        return out.join('/');
                    };
                    const base = resourcePath.slice(0, resourcePath.lastIndexOf('/') + 1);
                    const location = file.startsWith('/') ? clean(file) : clean(base + (res['@_BaseLoc'] || '') + '/' + file);
                    const entry = zip.file(location);
                    if (entry) {
                        try {
                            globalThis.PFFontCount = (globalThis.PFFontCount || 0) + 1;
                            const face = new FontFace('PFEmbedded' + globalThis.PFFontCount, await entry.async('arraybuffer'));
                            await face.load(); document.fonts.add(face);
                            fontResObj[font['@_ID']] = face.family;
                        } catch (e) { (globalThis.PFLayoutWarnings ||= []).push('Embedded font fallback: ' + location); }
                    } else (globalThis.PFLayoutWarnings ||= []).push('Missing embedded font: ' + location);
                } else (globalThis.PFLayoutWarnings ||= []).push('Font not embedded; system matching: ' + (font['@_FamilyName'] || font['@_FontName'] || font['@_ID']));
`;
await esbuild.build({
  stdin: { contents: `export * as OFD from './vendor/ofdjs/src/utils/ofd/ofd.js';\nexport {default as DjVu} from './vendor/djvujs/src/index.js';`, resolveDir: root, sourcefile: 'layout-entry.js' },
  bundle: true, minify: true, format: 'iife', globalName: 'PFLayoutModules', platform: 'browser', target: ['chrome100'],
  outfile: path.join(root, 'src/main/assets/layout/adapters.js'),
  alias: { '@': src }, define: { global: 'globalThis' }, legalComments: 'external',
  plugins: [{ name: 'paperforge-ofd-adaptation', setup(build) {
    build.onLoad({filter: /[\\/]@lapo[\\/]asn1js[\\/]asn1\.js$/}, async args => {
      let text = (await fs.readFile(args.path, 'utf8')).replaceAll('\r\n', '\n');
      text = replaceOnce(text, 'return require(name);', "if (name === './int10') return require('./int10'); if (name === './oids') return require('./oids'); throw new Error('Unknown ASN.1 dependency: ' + name);");
      return { contents: text, loader: 'js', resolveDir: path.dirname(args.path) };
    });
    build.onLoad({filter: /ofd_parser\.js$/}, async args => {
      let text = (await fs.readFile(args.path, 'utf8')).replaceAll('\r\n', '\n');
      text = replaceOnce(text, 'getFont(documentResObj)', 'getFont(documentResObj, zip, documentResPath)');
      text = replaceOnce(text, 'getFont(publicResObj)', 'getFont(publicResObj, zip, publicResPath)');
      text = replaceOnce(text, 'const getFont = async function (res)', 'const getFont = async function (res, zip, resourcePath)');
      text = replaceOnce(text, "fontResObj[font['@_ID']] = font['@_FontName'];\n                }", "fontResObj[font['@_ID']] = font['@_FontName'];\n                }\n" + fonts);
      return { contents: text, loader: 'js', resolveDir: path.dirname(args.path) };
    });
    build.onLoad({filter: /ofd_render\.js$/}, async args => {
      let text = (await fs.readFile(args.path, 'utf8')).replaceAll('\r\n', '\n');
      text = replaceOnce(text, '((screenWidth - 10) / parseFloat(array[2])).toFixed(1)', '(screenWidth / parseFloat(array[2]))');
      text = replaceOnce(text, 'text.innerHTML = textCodePoint.text;', 'text.textContent = textCodePoint.text;');
      return { contents: text, loader: 'js', resolveDir: path.dirname(args.path) };
    });
    build.onLoad({filter: /ofd_util\.js$/}, async args => {
      let text = (await fs.readFile(args.path, 'utf8')).replaceAll('\r\n', '\n');
      text = replaceOnce(text, 'MaxScale = scale > 5 ? 5 : scale;', 'MaxScale = scale;');
      return { contents: text, loader: 'js', resolveDir: path.dirname(args.path) };
    });
  }}],
});
console.log('Built pinned OFD / DjVu adapters.');
const notices=[];
for(const file of ['COPYING','vendor/ofdjs/LICENSE','vendor/djvujs/LICENSE.md','vendor/djvujs/GNU_GPL_v2'])
  notices.push(`===== ${file} =====\n${await fs.readFile(path.join(root,file),'utf8')}`);
const modules=[];
async function visit(directory) {
  for(const entry of await fs.readdir(directory,{withFileTypes:true})) {
    if(!entry.isDirectory()||entry.name.startsWith('.'))continue;
    const location=path.join(directory,entry.name);
    if(entry.name.startsWith('@')){await visit(location);continue;}
    try {
      const pkg=JSON.parse(await fs.readFile(path.join(location,'package.json'),'utf8'));
      modules.push({name:pkg.name,version:pkg.version,license:pkg.license});
      const files=(await fs.readdir(location)).filter(file=>/^(licen[cs]e|copying|notice)(\.|$)/i.test(file));
      for(const file of files) if((await fs.stat(path.join(location,file))).isFile())
        notices.push(`===== ${pkg.name} ${pkg.version} / ${file} =====\n${await fs.readFile(path.join(location,file),'utf8')}`);
      if(pkg.name==='@lapo/asn1js')notices.push(`===== @lapo/asn1js ISC notice =====\n${(await fs.readFile(path.join(location,'asn1.js'),'utf8')).split('(typeof define')[0]}`);
      if((await fs.stat(path.join(location,'node_modules')).catch(()=>null))?.isDirectory())await visit(path.join(location,'node_modules'));
    } catch(error) { if(error.code!=='ENOENT')throw error; }
  }
}
await visit(path.join(root,'node_modules'));
const target=path.join(root,'src/main/assets/layout');
await fs.writeFile(path.join(target,'licenses.txt'),notices.join('\n\n'));
const bytes=await fs.readFile(path.join(target,'adapters.js'));
await fs.writeFile(path.join(target,'provenance.json'),JSON.stringify({
  bundleSha256:crypto.createHash('sha256').update(bytes).digest('hex'),
  ofd:{repository:'https://github.com/DLTech21/ofd.js',commit:'c426b690dfca3d602f105476639f794479fed1b3',license:'Apache-2.0'},
  djvu:{repository:'https://github.com/RussCoder/djvujs',commit:'eca3e69c966f3b7fcf0453f39029bc1e409fea8a',license:'GPL-2.0-or-later'},modules,
},null,2)+'\n');
