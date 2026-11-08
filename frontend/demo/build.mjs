import { spawnSync } from 'node:child_process'
import { readFileSync, writeFileSync, readdirSync, copyFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { CSP, MARKER, validateEnvironment } from './policy.mjs'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..')
try {
  validateEnvironment(process.env)
  const allowed=['PATH','Path','SYSTEMROOT','SystemRoot','COMSPEC','ComSpec','PATHEXT','TEMP','TMP','HOME','USERPROFILE','APPDATA','LOCALAPPDATA']
  const env=Object.fromEntries(allowed.filter(k=>process.env[k]).map(k=>[k,process.env[k]]))
  env.SPLITSMART_DEMO_ONLY=MARKER
  const result=spawnSync(process.execPath,[resolve(root,'node_modules/vite/bin/vite.js'),'build','--config','vite.demo.config.js'],{cwd:root,env,stdio:'inherit'})
  if(result.status!==0)process.exit(1)
  const out=resolve(root,'dist-demo')
  copyFileSync(resolve(out,'demo.html'),resolve(out,'index.html'))
  const paths=['demo.html','index.html',...readdirSync(resolve(out,'assets')).map(n=>'assets/'+n)]
  const files={}
  for(const name of paths){const data=readFileSync(resolve(out,name));if(/\.js$/.test(name)&&['http://localhost:8081','splitsmart.accessToken','/api/auth/'].some(s=>data.includes(s)))throw new Error('DEMO_PRODUCTION_CODE_DETECTED');files[name]=createHash('sha256').update(data).digest('hex')}
  writeFileSync(resolve(out,'demo-manifest.json'),JSON.stringify({kind:'splitsmart-offline-'+MARKER,files},null,2))
  writeFileSync(resolve(out,'_headers'),'/*\n  Content-Security-Policy: '+CSP+'\n  Referrer-Policy: no-referrer\n  X-Content-Type-Options: nosniff\n  Cache-Control: no-store\n')
  console.log('OFFLINE_SYNTHETIC_ARTIFACT_VERIFIED')
} catch { console.error('DEMO_BUILD_BLOCKED'); process.exitCode=1 }
