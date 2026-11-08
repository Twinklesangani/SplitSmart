import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync,writeFileSync,unlinkSync,rmdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join,dirname,resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { createDemoServer,loadSite } from './server.mjs'
import { validateEnvironment } from './policy.mjs'
const enabled={SPLITSMART_DEMO_ONLY:'synthetic-v1'}
function fixture(){const root=mkdtempSync(join(tmpdir(),'splitsmart-demo-test-'));const html='<h1>Fictional only</h1>';writeFileSync(join(root,'index.html'),html);writeFileSync(join(root,'demo-manifest.json'),JSON.stringify({kind:'splitsmart-offline-synthetic-v1',files:{'index.html':createHash('sha256').update(html).digest('hex')}}));return root}
function cleanup(root){assert.equal(dirname(resolve(root)),resolve(tmpdir()));assert.ok(root.includes('splitsmart-demo-test-'));unlinkSync(join(root,'index.html'));unlinkSync(join(root,'demo-manifest.json'));rmdirSync(root)}
test('disabled by default and rejects mixed production routing without reflecting values',()=>{
 assert.throws(()=>validateEnvironment({}),/OPT_IN/)
 for(const key of ['DB_URL','DATABASE_URL','PGPASSWORD','FLYWAY_DB_PASSWORD','SPRING_PROFILES_ACTIVE','JWT_SECRET','BREVO_API_KEY','VITE_API_BASE_URL','VITE_EMAIL_ACTIONS_ENABLED'])assert.throws(()=>validateEnvironment({...enabled,[key]:'PRIVATE_MARKER'}),e=>!e.message.includes('PRIVATE_MARKER')&&e.message.includes('FORBIDDEN'))
})
test('refuses tampered artifact before serving',()=>{const r=fixture();try{writeFileSync(join(r,'index.html'),'tampered');assert.throws(()=>loadSite(r),/INTEGRITY/)}finally{cleanup(r)}})
test('refuses arbitrary manifest paths',()=>{const r=fixture();try{writeFileSync(join(r,'demo-manifest.json'),JSON.stringify({kind:'splitsmart-offline-synthetic-v1',files:{'index.html':'x','../private.txt':'x'}}));assert.throws(()=>loadSite(r))}finally{cleanup(r)}})
test('real HTTP rejects APIs/admin writes with CSP, no reflection or private assets',async()=>{
 const root=fixture(),server=createDemoServer(root,enabled);server.listen(0,'127.0.0.1');await once(server,'listening');const base='http://127.0.0.1:'+server.address().port
 try{
  const page=await fetch(base+'/');assert.equal(page.status,200);assert.match(page.headers.get('content-security-policy'),/connect-src 'none'/);assert.match(page.headers.get('content-security-policy'),/form-action 'none'/);assert.equal(page.headers.get('referrer-policy'),'no-referrer');assert.equal(page.headers.get('set-cookie'),null)
  for(const method of ['POST','PUT','PATCH','DELETE']){const r=await fetch(base+'/api/demo/reset',{method,body:'PRIVATE_MARKER'});assert.equal(r.status,405);assert.ok(!(await r.text()).includes('PRIVATE_MARKER'))}
  for(const path of ['/api/auth/login','/api/groups','/demo-manifest.json','/src/main.jsx','/.env'])assert.equal((await fetch(base+path)).status,404)
  const query=await fetch(base+'/?email=PRIVATE_MARKER');assert.equal(query.status,400);assert.ok(!(await query.text()).includes('PRIVATE_MARKER'));assert.equal((await fetch(base+'/',{method:'HEAD'})).status,200)
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));cleanup(root)}
})
