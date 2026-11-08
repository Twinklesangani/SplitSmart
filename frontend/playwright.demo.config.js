import { defineConfig } from '@playwright/test'
const url=process.env.SPLITSMART_DEMO_TEST_URL||'http://127.0.0.1:18086'
const parsed=new URL(url)
if(parsed.protocol!=='http:'||parsed.hostname!=='127.0.0.1')throw new Error('LOCAL_DEMO_BROWSER_ONLY')
export default defineConfig({testDir:'e2e/demo',outputDir:'demo-test-results',workers:1,retries:0,
 use:{baseURL:url,browserName:'chromium',channel:'msedge',headless:true,trace:'off',screenshot:'off',video:'off'},
 projects:[{name:'desktop',use:{viewport:{width:1280,height:900}}},{name:'mobile',use:{viewport:{width:375,height:812}}}],
 webServer:process.env.SPLITSMART_DEMO_TEST_URL?undefined:{command:'node demo/server.mjs',url,reuseExistingServer:false,env:{SPLITSMART_DEMO_ONLY:'synthetic-v1',PORT:'18086'}}})
