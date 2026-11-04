import Icon from './Icon.jsx'
import { expenseCategories, expenseDateLabel } from '../services/expenseMetadata.js'

export default function ExpenseMetadata({ expense }) {
  const [name, icon] = expenseCategories[expense.category] || expenseCategories.OTHER
  return <span className="expense-metadata">
    <span><Icon name={icon} size={16} /> {name}</span>
    <time dateTime={expense.expenseDate || undefined}>{expenseDateLabel(expense.expenseDate)}</time>
  </span>
}
