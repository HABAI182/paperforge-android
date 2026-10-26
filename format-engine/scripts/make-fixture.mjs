// Own synthetic pixels encoded by the pinned DjVu.js encoder, not an upstream photograph.
import * as esbuild from 'esbuild';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const output=path.join(root,'build/fixtures/writer.cjs');
await esbuild.build({stdin:{contents:`export {default as IWImageWriter} from './vendor/djvujs/src/iw44/IWImageWriter.js';`,resolveDir:root},bundle:true,platform:'node',format:'cjs',outfile:output});
globalThis.self=globalThis;
const {IWImageWriter}=createRequire(import.meta.url)(output);
const writer=new IWImageWriter(100,0,false);
writer.startMultiPageDocument();
for(let page=0;page<2;page++) {
  const width=320,height=480,data=new Uint8ClampedArray(width*height*4);
  for(let y=0;y<height;y++)for(let x=0;x<width;x++) {
    const p=(y*width+x)*4,color=x<width/2?[20,110,180]:[235,170,40];
    data.set([...color,255],p);
  }
  writer.addPageToDocument({width,height,data});
}
const buffer=Buffer.from(writer.endMultiPageDocument());
let found=0;
for(let i=0;i<buffer.length-18;i++)if(buffer.toString('ascii',i,i+4)==='INFO' && buffer.readUInt32BE(i+4)===10) {
  if(found++===1)buffer[i+17]=5; // Standard DjVu rotation flag for 90 degrees.
}
if(found!==2)throw new Error('Unexpected synthetic page headers');
const destination=path.join(root,'../app/src/androidTest/assets/layout-two-pages.djvu');
await fs.mkdir(path.dirname(destination),{recursive:true});
await fs.writeFile(destination,buffer);
console.log(`Created ${buffer.length} byte own two-page DjVu fixture.`);
