import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { validateEnvironment, MARKER } from './demo/policy.mjs'
validateEnvironment(process.env)
export default defineConfig({ plugins:[react()], envDir:false, publicDir:false,
 define:{'import.meta.env.VITE_PORTFOLIO_DEMO':JSON.stringify(MARKER)},
 build:{outDir:'dist-demo',modulePreload:false,rollupOptions:{input:resolve(fileURLToPath(new URL('.',import.meta.url)),'demo.html')}} })
