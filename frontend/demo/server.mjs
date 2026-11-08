import { createServer } from 'node:http'
import { readFileSync, realpathSync, lstatSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve, dirname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { CSP, validateEnvironment } from './policy.mjs'
export function loadSite(root) {
 const base=realpathSync(root),manifest=JSON.parse(readFileSync(resolve(base,'demo-manifest.json')))
 if(manifest.kind!=='splitsmart-offline-synthetic-v1'||!manifest.files['index.html'])throw new Error('DEMO_ARTIFACT_REQUIRED')
 const files=new Map()
 for(const [name,hash] of Object.entries(manifest.files)){
  if(!/^(index\.html|demo\.html|assets\/[A-Za-z0-9_.-]+\.(js|css|svg|woff2))$/.test(name))throw new Error('DEMO_ASSET_SCOPE')
  const path=resolve(base,name)
  if(lstatSync(path).isSymbolicLink()||!realpathSync(path).startsWith(base+sep))throw new Error('DEMO_ASSET_SCOPE')
  const data=readFileSync(path)
  if(createHash('sha256').update(data).digest('hex')!==hash)throw new Error('DEMO_ASSET_INTEGRITY')
  files.set('/'+name,data)
 }
 files.set('/',files.get('/index.html'));return files
}
export function createDemoServer(root,env) {
 validateEnvironment(env)
 const files=loadSite(root)
 return createServer({maxHeaderSize:8192},(req,res)=>{
  const headers={'Content-Security-Policy':CSP,'Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff','Cross-Origin-Resource-Policy':'same-origin','Permissions-Policy':'camera=(), microphone=(), geolocation=()','Cache-Control':'no-store'}
  const end=(status,text)=>{res.writeHead(status,{...headers,'Content-Type':'text/plain; charset=utf-8'});res.end(req.method==='HEAD'?'':text)}
  if(!['GET','HEAD'].includes(req.method)){req.resume();return end(405,'Read-only synthetic demonstration')}
  if(req.url.includes('?')||req.url.includes('%')||req.url.includes('..'))return end(400,'Invalid demo path')
  const data=files.get(req.url)
  if(!data)return end(404,'Not a demo asset')
  const type=req.url.endsWith('.js')?'text/javascript':req.url.endsWith('.css')?'text/css':req.url.endsWith('.svg')?'image/svg+xml':req.url.endsWith('.woff2')?'font/woff2':'text/html'
  res.writeHead(200,{...headers,'Content-Type':type+'; charset=utf-8','Content-Length':data.length});res.end(req.method==='HEAD'?undefined:data)
 })
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 try{
  const port=Number(process.env.PORT||18086),host=process.env.DEMO_BIND_ADDRESS||'127.0.0.1'
  if(!Number.isInteger(port)||port<1024||port>65535||!['127.0.0.1','0.0.0.0'].includes(host))throw new Error('DEMO_BIND_SCOPE')
  const server=createDemoServer(resolve(dirname(fileURLToPath(import.meta.url)),'../dist-demo'),process.env)
  server.requestTimeout=5000;server.headersTimeout=5000;server.keepAliveTimeout=1000
  server.listen(port,host,()=>console.log('SYNTHETIC_STATIC_SERVER_READY'))
  server.on('error',()=>{console.error('DEMO_SERVER_BLOCKED');process.exitCode=1})
 }catch{console.error('DEMO_SERVER_BLOCKED');process.exitCode=1}
}
