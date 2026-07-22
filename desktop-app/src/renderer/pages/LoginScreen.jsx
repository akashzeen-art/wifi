import { useState, useEffect } from 'react'
import { motion } from 'framer-motion'
import { Wifi, Eye, EyeOff, ArrowRight, AlertCircle } from 'lucide-react'
import api from '../lib/api'
import useAppStore from '../store/appStore'
import TitleBar from '../components/TitleBar'

export default function LoginScreen() {
  const [form,     setForm]     = useState({ email: '', password: '' })
  const [apiUrl,   setApiUrl]   = useState('https://wifi.vault-x.world')
  const [showPass, setShowPass] = useState(false)
  const [error,    setError]    = useState('')
  const [loading,  setLoading]  = useState(false)
  const setAuth = useAppStore(s => s.setAuth)

  useEffect(() => {
    ;(async () => {
      try {
        const settings = await window.electron.settings.get()
        let url = settings?.apiUrl || 'https://wifi.vault-x.world'
        if (url.includes('localhost') || url.includes('127.0.0.1')) {
          url = 'https://wifi.vault-x.world'
        }
        setApiUrl(url.replace(/\/+$/, ''))
        window.__apiUrl = url.replace(/\/+$/, '')
      } catch (_) {
        window.__apiUrl = 'https://wifi.vault-x.world'
      }
    })()
  }, [])

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const server = (apiUrl || 'https://wifi.vault-x.world').trim().replace(/\/+$/, '')
      window.__apiUrl = server
      try {
        const settings = await window.electron.settings.get()
        await window.electron.settings.save({ ...settings, apiUrl: server })
      } catch (_) {}

      const { data } = await api.post('/auth/login', form)
      const user = {
        name:  data.user?.name  || data.name  || form.email.split('@')[0],
        email: data.user?.email || data.email || form.email,
        role:  data.user?.role  || data.role  || 'USER'
      }
      const token = data.accessToken || data.token
      window.__token = token
      await window.electron.store.set('token', token)
      await window.electron.store.set('user', user)
      setAuth(token, user)
    } catch (err) {
      const status = err?.response?.status
      if (!err?.response) {
        setError('Cannot reach server. Check internet or Server URL below.')
      } else if (status === 401 || status === 400) {
        setError('Invalid email or password')
      } else {
        setError(`Login failed (${status || 'error'}). Try again.`)
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="h-screen flex flex-col bg-surface-50">
      <TitleBar />

      <div className="flex-1 flex items-center justify-center px-6">
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
          className="w-full max-w-sm"
        >
          {/* Logo */}
          <div className="text-center mb-8">
            <div className="w-16 h-16 bg-gradient-to-br from-brand-600 to-cyan-500 rounded-2xl flex items-center justify-center mx-auto mb-4 shadow-button">
              <Wifi className="w-8 h-8 text-white" />
            </div>
            <h1 className="text-xl font-bold text-slate-900">WiFiExtender</h1>
            <p className="text-slate-500 text-sm mt-1">Sign in to activate your license</p>
          </div>

          <div className="card p-6">
            {error && (
              <motion.div
                initial={{ opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                className="flex items-center gap-2 bg-red-50 border border-red-200 text-red-600 text-xs px-3 py-2.5 rounded-xl mb-4"
              >
                <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
                {error}
              </motion.div>
            )}

            <form onSubmit={handleSubmit} className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">Server URL</label>
                <input
                  type="url"
                  className="input-field"
                  placeholder="https://wifi.vault-x.world"
                  value={apiUrl}
                  onChange={e => setApiUrl(e.target.value)}
                  required
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">Email</label>
                <input
                  type="email"
                  className="input-field"
                  placeholder="you@example.com"
                  value={form.email}
                  onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
                  autoComplete="email"
                  required
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">Password</label>
                <div className="relative">
                  <input
                    type={showPass ? 'text' : 'password'}
                    className="input-field pr-10"
                    placeholder="••••••••"
                    value={form.password}
                    onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                    autoComplete="current-password"
                    required
                  />
                  <button
                    type="button"
                    onClick={() => setShowPass(v => !v)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 transition-colors"
                  >
                    {showPass ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>

              <button type="submit" className="btn-primary w-full mt-1" disabled={loading}>
                {loading ? (
                  <span className="flex items-center gap-2">
                    <svg className="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
                    </svg>
                    Signing in...
                  </span>
                ) : (
                  <>Sign In <ArrowRight className="w-4 h-4" /></>
                )}
              </button>
            </form>
          </div>

          <p className="text-center text-xs text-slate-400 mt-4">
            Need an account?{' '}
            <span className="text-brand-600 font-medium">Visit wifi.vault-x.world</span>
          </p>
        </motion.div>
      </div>
    </div>
  )
}
