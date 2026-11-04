export const expenseCategories = {
  FOOD_DRINKS: ['Food & Drinks', 'utensils'], TRAVEL: ['Travel', 'car'], SHOPPING: ['Shopping', 'basket'],
  RENT_UTILITIES: ['Rent & Utilities', 'home'], ENTERTAINMENT: ['Entertainment', 'spark'], OTHER: ['Other', 'receipt'],
}

export function expenseDateLabel(value) {
  if (!value) return 'Date not recorded'
  return new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${value}T12:00:00Z`))
}
