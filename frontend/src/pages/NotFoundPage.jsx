import { Link } from 'react-router-dom'
import Icon from '../components/Icon.jsx'

export default function NotFoundPage() {
  return (
    <main className="not-found">
      <Link className="brand" to="/dashboard" aria-label="SplitSmart home"><span className="brand-mark"><Icon name="wallet" size={21} strokeWidth={2} /></span><span>split<span>smart</span></span></Link>
      <div className="not-found__content"><span className="not-found__code">404</span><h1>This page took a detour.</h1><p>The address may be outdated, or the page may have moved.</p><Link className="button button--primary" to="/dashboard">Back to overview <Icon name="arrowRight" size={18} /></Link></div>
    </main>
  )
}
