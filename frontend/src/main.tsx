import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import './index.css'
import App from './App'
import { AuthProvider } from './auth'
import { TourProvider } from './tour'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <TourProvider>
          <App />
        </TourProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
