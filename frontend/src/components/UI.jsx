import { Link } from 'react-router-dom'
import Icon from './Icon.jsx'

export function Button({ children, to, variant = 'primary', icon, className = '', ...props }) {
  const classes = `button button--${variant} ${className}`.trim()
  const content = <>{icon && <Icon name={icon} size={18} />}{children}</>
  return to
    ? <Link className={classes} to={to} {...props}>{content}</Link>
    : <button className={classes} {...props}>{content}</button>
}

export function Card({ children, className = '', ...props }) {
  return <section className={`card ${className}`.trim()} {...props}>{children}</section>
}

export function PageHeader({ eyebrow, title, description, action }) {
  return (
    <div className="page-header">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p className="page-description">{description}</p>}
      </div>
      {action && <div className="page-header__action">{action}</div>}
    </div>
  )
}

export function EmptyState({ icon = 'receipt', title, description, action }) {
  return (
    <div className="empty-state">
      <span className="empty-state__icon"><Icon name={icon} size={24} /></span>
      <h3>{title}</h3>
      <p>{description}</p>
      {action}
    </div>
  )
}

export function SummaryCard({ label, value, detail, icon, tone }) {
  return (
    <Card className="summary-card">
      <div className={`summary-card__icon tone-${tone}`}><Icon name={icon} size={20} /></div>
      <p className="summary-card__label">{label}</p>
      <p className={`summary-card__value ${tone === 'green' ? 'text-green' : tone === 'red' ? 'text-red' : ''}`}>{value}</p>
      <p className="summary-card__detail">{detail}</p>
    </Card>
  )
}

export function GroupCard({ group }) {
  const initial = group.name.trim().slice(0, 1).toUpperCase() || '?'
  return (
    <Link className="group-card" to={`/groups/${group.id}`}>
      <span className="group-avatar group-avatar--lavender" aria-hidden="true">{initial}</span>
      <span className="group-card__main">
        <span className="group-card__name">{group.name}</span>
        <span className="group-card__meta">Group ID {group.id}</span>
        {group.archived && <span className="eyebrow">Archived · Read-only</span>}
      </span>
      <Icon name="arrowRight" size={18} className="group-card__arrow" />
    </Link>
  )
}

export function FormField({
  id,
  label,
  type = 'text',
  autoComplete,
  placeholder,
  required = true,
  value,
  onChange,
  error,
  hint,
  inputRef,
  maxLength,
}) {
  const errorId = `${id}-error`
  const hintId = `${id}-hint`
  const describedBy = [error ? errorId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className="form-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={id}
        type={type}
        autoComplete={autoComplete}
        placeholder={placeholder}
        required={required}
        value={value}
        onChange={onChange}
        maxLength={maxLength}
        ref={inputRef}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy}
      />
      {hint && <span className="form-help" id={hintId}>{hint}</span>}
      {error && <span className="field-error" id={errorId} role="alert">{error}</span>}
    </div>
  )
}
