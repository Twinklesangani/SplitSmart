import { Link, Outlet } from 'react-router-dom'
import Icon from '../components/Icon.jsx'

export default function AuthLayout() {
  return (
    <main className="auth-layout">
      <Link className="brand auth-brand" to="/dashboard" aria-label="SplitSmart home"><span className="brand-mark"><Icon name="wallet" size={21} strokeWidth={2} /></span><span>split<span>smart</span></span></Link>
      <div className="auth-layout__content">
        <div className="auth-intro">
          <span className="auth-intro__icon"><Icon name="spark" size={23} /></span>
          <p className="eyebrow">LESS SETTLING UP. MORE SHOWING UP.</p>
          <h1>Shared plans,<br />without the math.</h1>
          <p>Keep group expenses clear, so everyone can focus on the moments that matter.</p>
        </div>
        <Outlet />
      </div>
      <p className="auth-footnote">A clearer way to share the things that matter</p>
    </main>
  )
}
