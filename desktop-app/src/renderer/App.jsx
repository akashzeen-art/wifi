import { useState, useEffect } from 'react'
import useAppStore from './store/appStore'
import LoginScreen   from './pages/LoginScreen'
import LicenseScreen from './pages/LicenseScreen'
import HotspotScreen from './pages/HotspotScreen'
import SetupWizard   from './pages/SetupWizard'

function Spinner() {
  return (
    <div className="h-screen flex flex-col items-center justify-center bg-surface-50">
      <div className="w-10 h-10 bg-gradient-to-br from-brand-600 to-cyan-500 rounded-2xl flex items-center justify-center mb-4 shadow-button">
        <svg className="w-5 h-5 text-white animate-spin" viewBox="0 0 24 24" fill="none">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
        </svg>
      </div>
      <p className="text-xs text-slate-400 font-medium">Loading WiFiExtender...</p>
    </div>
  )
}

export default function App() {
  const { token, licenseValid, setupComplete, setAuth, setLicense, setSetupComplete } = useAppStore()
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const restore = async () => {
      try {
        const [savedToken, savedUser, savedLicense, savedLicenseValid, savedLicenseData, savedSetup] =
          await Promise.all([
            window.electron.store.get('token'),
            window.electron.store.get('user'),
            window.electron.store.get('licenseKey'),
            window.electron.store.get('licenseValid'),
            window.electron.store.get('licenseData'),
            window.electron.store.get('setupComplete'),
          ])

        const settings = await window.electron.settings.get()
        const autoLogin = settings?.autoLogin !== false
        if (settings?.apiUrl) window.__apiUrl = settings.apiUrl

        if (autoLogin && savedToken && savedUser) {
          window.__token = savedToken
          setAuth(savedToken, savedUser)
        }
        if (savedLicense && savedLicenseValid) {
          setLicense(savedLicense, true, savedLicenseData || null)
        }
        if (savedSetup) setSetupComplete(true)
      } catch (err) {
        console.error('Restore error:', err)
      } finally {
        setLoading(false)
      }
    }
    restore()
  }, [setAuth, setLicense])

  if (loading)        return <Spinner />
  if (!token)         return <LoginScreen />
  if (!licenseValid)  return <LicenseScreen />
  if (!setupComplete) return <SetupWizard onComplete={() => setSetupComplete(true)} />
  return <HotspotScreen />
}
