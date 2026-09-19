// Integration smoke test with a real Firefox and a simulated authenticated phone.
// Run: node backend/test/webui/shared-console-smoke.mjs
import { browser } from './bidi.mjs';
import { html, status } from './harness.mjs';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import assert from 'node:assert/strict';
const dir = mkdtempSync('/tmp/deskcam-shared-');
const calls = []; let zoom=1;
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64');
const phone=createServer((req,res)=>{
 const u=new URL(req.url,'http://localhost');
 if(u.searchParams.get('token')!=='test-secret') {res.writeHead(401).end('{}');return;}
 calls.push(u);
 if(u.pathname==='/'){res.setHeader('Content-Type','text/html');res.end(html);return;}
 if(u.pathname==='/api/stream'){res.setHeader('Content-Type','image/png');res.end(png);return;}
 if(u.pathname==='/api/set'&&u.searchParams.has('zoom'))zoom=Number(u.searchParams.get('zoom'));
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify(u.pathname==='/api/marks'?{marks:[]}:status(zoom)));
});
phone.listen(0,'127.0.0.1'); await once(phone,'listening');
mkdirSync(dir+'/deskcam');writeFileSync(dir+'/deskcam/url',`http://127.0.0.1:${phone.address().port}`);writeFileSync(dir+'/deskcam/token','test-secret');
const slot=createServer();slot.listen(0,'127.0.0.1');await once(slot,'listening');const port=slot.address().port;await new Promise(r=>slot.close(r));
execFileSync('go', ['build', '-o', dir+'/deskcam-bin', '.'], {cwd: new URL('../../../frontend/go/', import.meta.url)});
const proc=spawn(dir+'/deskcam-bin',['serve',String(port)],{cwd:dir,env:{...process.env,XDG_CONFIG_HOME:dir,DESKCAM_JOURNAL:dir+'/journal',DESKCAM_URL:'',DESKCAM_TOKEN:''},stdio:'ignore'});
let b;
try{
 for(let i=0;i<100;i++){try{await fetch(`http://127.0.0.1:${port}`);break;}catch{await new Promise(r=>setTimeout(r,100));}}
 b=await browser({width:1500,height:1000});await b.navigate(`http://127.0.0.1:${port}`);
 for(let i=0;i<50;i++){if(await b.json(`document.querySelector('#camera').contentDocument.querySelector('#view')?.naturalWidth > 0`))break;await new Promise(r=>setTimeout(r,100));}
 assert.equal(await b.json(`document.querySelector('#camera').contentDocument.querySelector('#view').naturalWidth`),1);
 await b.json(`document.querySelector('#camera').contentWindow.api('/api/set?zoom=2')`);
 for(let i=0;i<50&&zoom!==2;i++)await new Promise(r=>setTimeout(r,100));
 assert.equal(zoom,2);
 const box=await b.json(`(()=>{const f=document.querySelector('#camera'),r=f.getBoundingClientRect(),p=f.contentWindow.picture();return {x:r.x+p.left,y:r.y+p.top,w:p.w,h:p.h}})()`);
 await b.shiftDrag(box.x+box.w*.25,box.y+box.h*.25,box.x+box.w*.75,box.y+box.h*.75);
 await new Promise(r=>setTimeout(r,300));
 assert.ok(calls.some(u=>u.pathname==='/api/marks'&&u.searchParams.has('mark')));
 assert.equal(await b.json(`document.querySelector('#camera').contentDocument.documentElement.outerHTML.includes('test-secret')`),false);
 await b.screenshot('/tmp/deskcam-shared-console.png');
 await b.viewport(700,900);await b.screenshot('/tmp/deskcam-shared-console-narrow.png');
 assert.equal(await b.json(`document.querySelector('#camera').getBoundingClientRect().height > 500`),true);
 await b.navigate(`http://127.0.0.1:${phone.address().port}/?token=test-secret`);
 await b.json(`api('/api/set?zoom=3')`);for(let i=0;i<50&&zoom!==3;i++)await new Promise(r=>setTimeout(r,100));assert.equal(zoom,3);
 assert.deepEqual(b.errors().filter(e=>e.type==='javascriptError'),[]);
 console.log('Firefox passed: console iframe, authenticated controls, real Shift-drag, narrow layout, direct token panel, no JavaScript errors.');
}finally{await b?.close();proc.kill();phone.closeAllConnections();await new Promise(r=>phone.close(r));rmSync(dir,{recursive:true,force:true});}
