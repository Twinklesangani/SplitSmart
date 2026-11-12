import fs from 'node:fs'
import { createServer } from 'vite'
if (process.env.SS_RECOVERY_HTTPS !== 'synthetic-only' || !process.env.SS_PFX || !process.env.SS_PFX_PASSWORD
  || process.env.VITE_API_BASE_URL !== 'https://localhost:18443') throw new Error('Local HTTPS guard rejected configuration')
const server = await createServer({
  logLevel: 'silent',
  server: { host: '127.0.0.1', port: 15175, strictPort: true,
    https: { pfx: fs.readFileSync(process.env.SS_PFX), passphrase: process.env.SS_PFX_PASSWORD } },
})
await server.listen()
process.stdout.write('LOCAL_REACT_HTTPS_READY\n')
