import { useState, useEffect } from 'react'
import { motion } from 'framer-motion'
import { Save, Check, Key, Monitor, Wifi, Infinity, Calendar, RefreshCw } from 'lucide-react'

function Toggle({ checked, onChange, disabled }) {
  return (
    <button
      type="button"
      onClick={() => !disabled && onChange(!checked)}
      disabled={disabled}
      className={`toggle ${checked ? 'bg-brand-600' : 'bg-slate-200'} ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`}
    >
      <motion.div
        className="toggle-thumb"
        animate={{ left: checked ? '1.375rem' : '0.25rem' }}
        transition={{ type: 'spring', stiffness: 500, damping: 30 }}
      />
    </button>
  )
}

function Section({ title, children }) {
  return (
    <div className="card p-4 space-y-3">
      <h3 className="text-xs font-semibold text-slate-500 uppercase tracking-wider">{title}</h3>
      {children}
    </div>
  )
}

function Row({ label, desc, children }) {
  return (
    <div className="flex items-center justify-between gap-4 py-1">
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-slate-800">{label}</p>
        {desc && <p className="text-[11px] text-slate-400 mt-0.5">{desc}</p>}
      </div>
      <div className="flex-shrink-0">{children}</div>
    </div>
  )
}

export default function SettingsTab({ licenseKey, licenseData }) {
  const [settings, setSettings] = useState({
    autoStart:       false,
    minimizeToTray:  true,
    autoLogin:       true,
    refreshInterval: 8,
    apiUrl:          'https://wifi.vault-x.world',
  })
  const [saved,   setSaved]   = useState(false)
  const [loading, setLoading] = useState(true)
  const [machineInfo, setMachineInfo] = useState(null)

  useEffect(() => {
    Promise.all([
      window.electron.settings.get(),
      window.electron.machine.info(),
    ]).then(([s, m]) => {
      if (s) {
        let apiUrl = s.apiUrl || 'https://wifi.vault-x.world'
        if (apiUrl.includes('localhost') || apiUrl.includes('127.0.0.1')) {
          apiUrl = 'https://wifi.vault-x.world'
        }
        setSettings({ ...s, apiUrl })
        window.__apiUrl = apiUrl.replace(/\/+$/, '')
      }
      setMachineInfo(m)
    }).finally(() => setLoading(false))
  }, [])

  const set = (key) => (val) => setSettings(s => ({ ...s, [key]: val }))

  const handleSave = async () => {
    const apiUrl = (settings.apiUrl || 'https://wifi.vault-x.world').replace(/\/+$/, '')
    const next = { ...settings, apiUrl }
    await window.electron.settings.save(next)
    window.__apiUrl = apiUrl
    setSettings(next)
    setSaved(true)
    setTimeout(() => setSaved(false), 2000)
  }

  if (loading) return (
    <div className="flex items-center justify-center h-48">
      <div className="w-6 h-6 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
    </div>
  )

  return (
    <div className="p-5 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-bold text-slate-900">Settings</h2>
          <p className="text-xs text-slate-400 mt-0.5">Configure app behaviour and preferences</p>
        </div>
        <motion.button
          whileTap={{ scale: 0.96 }}
          onClick={handleSave}
          className="btn-primary text-xs py-2 px-4"
        >
          {saved
            ? <><Check className="w-3.5 h-3.5" /> Saved!</>
            : <><Save className="w-3.5 h-3.5" /> Save</>
          }
        </motion.button>
      </div>

      {/* Behaviour */}
      <Section title="Behaviour">
        <Row label="Auto-start hotspot" desc="Automatically start hotspot when app launches">
          <Toggle checked={settings.autoStart} onChange={set('autoStart')} />
        </Row>
        <div className="border-t border-slate-50" />
        <Row label="Minimize to tray" desc="Keep app running in system tray when closed">
          <Toggle checked={settings.minimizeToTray} onChange={set('minimizeToTray')} />
        </Row>
        <div className="border-t border-slate-50" />
        <Row label="Auto-login" desc="Stay signed in between sessions">
          <Toggle checked={settings.autoLogin} onChange={set('autoLogin')} />
        </Row>
      </Section>

      {/* Connection */}
      <Section title="Connection">
        <Row label="Device refresh interval" desc="How often to poll connected devices (seconds)">
          <select
            className="input-field text-xs py-1.5 w-20"
            value={settings.refreshInterval}
            onChange={e => set('refreshInterval')(parseInt(e.target.value))}
          >
            {[4, 8, 15, 30].map(v => (
              <option key={v} value={v}>{v}s</option>
            ))}
          </select>
        </Row>
        <div className="border-t border-slate-50" />
        <Row label="API server URL" desc="Backend server address">
          <input
            className="input-field text-xs py-1.5 w-48"
            value={settings.apiUrl}
            onChange={e => set('apiUrl')(e.target.value)}
            placeholder="https://wifi.vault-x.world"
          />
        </Row>
      </Section>

      {/* License info */}
      <Section title="License">
        <div className="bg-slate-50 rounded-xl p-3 border border-slate-100 space-y-2">
          <div className="flex items-center gap-2">
            <Key className="w-3.5 h-3.5 text-brand-500 flex-shrink-0" />
            <span className="font-mono text-xs text-slate-700 break-all">{licenseKey}</span>
          </div>
          {licenseData && (
            <div className="grid grid-cols-2 gap-2 pt-1 border-t border-slate-200">
              <div>
                <p className="text-[10px] text-slate-400">Plan</p>
                <p className="text-xs font-semibold text-slate-700">{licenseData.planName || '—'}</p>
              </div>
              <div>
                <p className="text-[10px] text-slate-400">Devices</p>
                <p className="text-xs font-semibold text-slate-700 flex items-center gap-1">
                  {licenseData.unlimitedDevices
                    ? <><Infinity className="w-3 h-3" /> Unlimited</>
                    : licenseData.maxDevices}
                </p>
              </div>
              <div>
                <p className="text-[10px] text-slate-400">Expires</p>
                <p className="text-xs font-semibold text-slate-700 flex items-center gap-1">
                  {licenseData.lifetime
                    ? <><Infinity className="w-3 h-3 text-violet-500" /> Lifetime</>
                    : licenseData.expiresAt
                      ? new Date(licenseData.expiresAt).toLocaleDateString()
                      : '—'}
                </p>
              </div>
              <div>
                <p className="text-[10px] text-slate-400">Status</p>
                <span className="badge-green text-[10px]">Active</span>
              </div>
            </div>
          )}
        </div>
      </Section>

      {/* Machine info */}
      {machineInfo && (
        <Section title="This Device">
          <div className="grid grid-cols-2 gap-2 text-xs">
            {[
              { label: 'Hostname', value: machineInfo.hostname },
              { label: 'Platform', value: `${machineInfo.platform} ${machineInfo.arch}` },
              { label: 'CPU cores', value: machineInfo.cpus },
              { label: 'Free memory', value: `${(machineInfo.freeMem / 1024 / 1024 / 1024).toFixed(1)} GB` },
            ].map(({ label, value }) => (
              <div key={label} className="bg-slate-50 rounded-xl p-2.5 border border-slate-100">
                <p className="text-[10px] text-slate-400 mb-0.5">{label}</p>
                <p className="font-semibold text-slate-700 truncate">{value}</p>
              </div>
            ))}
          </div>
        </Section>
      )}
    </div>
  )
}
