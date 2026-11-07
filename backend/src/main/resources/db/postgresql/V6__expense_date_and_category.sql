ALTER TABLE expenses ADD COLUMN expense_date DATE;
ALTER TABLE expenses ADD COLUMN category VARCHAR(24) NOT NULL DEFAULT 'OTHER';
-- Use the genuine recorded creation date. No historical event date is inferred.
UPDATE expenses SET expense_date = CAST(created_at AS DATE) WHERE expense_date IS NULL;
ALTER TABLE expenses ADD CONSTRAINT chk_expense_category CHECK
    (category IN ('FOOD_DRINKS','TRAVEL','SHOPPING','RENT_UTILITIES','ENTERTAINMENT','OTHER'));

