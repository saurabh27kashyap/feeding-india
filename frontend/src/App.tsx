import { Navigate, Route, Routes } from 'react-router'
import { useAuth } from './auth'
import DemoBar from './DemoBar'
import StoryTimeline from './StoryTimeline'
import { Narrator } from './tour'
import Login from './pages/Login'
import Story from './pages/Story'
import CentreHome from './pages/partner/CentreHome'
import KitchenHome from './pages/partner/KitchenHome'
import OpsLayout from './pages/ops/OpsLayout'
import Dashboard from './pages/ops/Dashboard'
import Invoices from './pages/ops/Invoices'
import InvoiceDetail from './pages/ops/InvoiceDetail'
import OrderDetail from './pages/ops/OrderDetail'
import Scorecard from './pages/ops/Scorecard'
import { Spinner } from './ui'

function homeFor(role?: string) {
  return role === 'OPS' ? '/ops' : '/partner'
}

export default function App() {
  const { me } = useAuth()
  if (!me) return <Spinner />

  return (
    <div className="pb-40">
      {me.authenticated && <StoryTimeline />}
      <Routes>
        <Route path="/story" element={<Story />} />
        <Route path="/login" element={me.authenticated ? <Navigate to={homeFor(me.role)} replace /> : <Login />} />
        {/* Partner App: one app for NGO (centre) and vendor (kitchen) partners. */}
        <Route
          path="/partner"
          element={
            !me.authenticated ? <Navigate to="/login" replace />
              : me.role === 'CENTRE' ? <CentreHome />
              : me.role === 'KITCHEN' ? <KitchenHome />
              : <Navigate to="/ops" replace />
          }
        />
        {/* Ops Console: internal team. */}
        <Route path="/ops" element={me.authenticated && me.role === 'OPS' ? <OpsLayout /> : <Navigate to="/login" replace />}>
          <Route index element={<Dashboard />} />
          <Route path="invoices" element={<Invoices />} />
          <Route path="invoices/:id" element={<InvoiceDetail />} />
          <Route path="orders/:id" element={<OrderDetail />} />
          <Route path="kitchens" element={<Scorecard />} />
        </Route>
        <Route path="*" element={<Navigate to={me.authenticated ? homeFor(me.role) : '/story'} replace />} />
      </Routes>
      <DemoBar />
      <Narrator />
    </div>
  )
}
