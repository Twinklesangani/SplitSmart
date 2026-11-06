import { Navigate, Route, Routes } from 'react-router-dom'
import { ProtectedRoute, PublicOnlyRoute } from './components/RouteGuards.jsx'
import AppShell from './layouts/AppShell.jsx'
import AuthLayout from './layouts/AuthLayout.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import GroupsPage from './pages/GroupsPage.jsx'
import { GroupOverviewPage } from './pages/GroupPages.jsx'
import { GroupBalancesPage, GroupSettlementsPage } from './pages/FinancePages.jsx'
import LoginPage from './pages/LoginPage.jsx'
import RegisterPage from './pages/RegisterPage.jsx'
import NotFoundPage from './pages/NotFoundPage.jsx'
import InvitationLinkPage from './pages/InvitationLinkPage.jsx'
import { ForgotPasswordPage, CheckEmailPage, ResetPasswordPage, ResetSuccessPage } from './pages/PasswordRecoveryPages.jsx'
import { ExpenseCreatePage, ExpenseDetailPage, ExpenseEditPage, GroupExpensesPage } from './pages/ExpensePages.jsx'

function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/groups" element={<GroupsPage />} />
          <Route path="/groups/:groupId" element={<GroupOverviewPage />} />
          <Route path="/groups/:groupId/expenses" element={<GroupExpensesPage />} />
          <Route path="/groups/:groupId/expenses/new" element={<ExpenseCreatePage />} />
          <Route path="/groups/:groupId/expenses/:expenseId" element={<ExpenseDetailPage />} />
          <Route path="/groups/:groupId/expenses/:expenseId/edit" element={<ExpenseEditPage />} />
          <Route path="/groups/:groupId/balances" element={<GroupBalancesPage />} />
          <Route path="/groups/:groupId/settlements" element={<GroupSettlementsPage />} />
        </Route>
      </Route>
      <Route element={<PublicOnlyRoute />}>
        <Route element={<AuthLayout />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
        </Route>
      </Route>
      <Route element={<AuthLayout />}>
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/join" element={<InvitationLinkPage />} />
        <Route path="/verify-email" element={<InvitationLinkPage verify />} />
        <Route path="/check-email" element={<CheckEmailPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/reset-success" element={<ResetSuccessPage />} />
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default App
