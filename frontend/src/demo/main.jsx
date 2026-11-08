import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../index.css'
import DemoApp from './DemoApp.jsx'

if (import.meta.env.VITE_PORTFOLIO_DEMO !== 'synthetic-v1') throw new Error('Synthetic demo build required')
createRoot(document.getElementById('root')).render(<StrictMode><DemoApp /></StrictMode>)
