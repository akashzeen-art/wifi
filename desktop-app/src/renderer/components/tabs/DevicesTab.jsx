import { useState, useEffect, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import {
  Monitor, Smartphone, Laptop, Tv, Wifi,
  Ban, ArrowDownRight, ArrowUpRight, Search,
  Clock, Radio, RefreshCw
} from 'lucide-react'
import api from '../../lib/api'

function formatBytes(b) {
  if (!b || b <= 0) return '0 B'
  if (b < 1024)        return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(1)} MB`
}

function timeAgo(dt) {
  if (!dt) return '—'
  const secs = Math.floor((Date.now() - new Date(dt)) / 1000)
  if (secs < 60)   return `${secs}s ago`
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`
  return `${Math.floor(secs / 3600)}h ago`
}

function connectionDuration(connectedAt) {
  if (!connectedAt) return '—'
  const secs = Math.floor((Date.now() - new Date(connectedAt)) / 1000)
  if (secs < 60)   return `${secs}s`
  if (secs < 3600) return `${Math.floor(secs / 60)}m`
  return `${Math.floor(secs / 3600)}h ${Math.floor((secs % 3600) / 60)}m`
}

function DeviceIcon({ type, blocked, online }) {
  const Icon = type === 'PHONE' ? Smartphone
             : type === 'LAPTOP' ? Laptop
             : type === 'TV' ? Tv
             : Monitor
  return (
    <div className={`relative w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 ${
      blocked ? 'bg-red-50' : online ? 'bg-brand-50' : 'bg-slate-100'
    }`}>
      <Icon className={`w-4 h-4 ${blocked ? 'text-red-400' : online ? 'text-brand-500' : 'text-slate-400'}`} />
      <div className={`absolute -top-0.5 -right-0.5 w-2.5 h-2.5 rounded-full border-2 border-white ${
        blocked ? 'bg-red-400' : online ? 'bg-emerald-400' : 'bg-slate-300'
      }`} />
    </div>
  )
}

function SignalBars({ strength }) {
  if (strength == null) return null
  const bars  = [25, 50, 75, 100]
  const color = strength >= 75 ? 'bg-emerald-400' : strength >= 50 ? 'bg-amber-400' : 'bg-red-400'
  return (
    <div className="flex items-end gap-0.5 h-3">
      {bars.map((t, i) => (
        <div key={i} className={`w-1 rounded-sm ${strength >= t ? color : 'bg-slate-200'}`}
          style={{ height: `${(i + 1) * 25}%` }} />
      ))}
    </div>
  )
}

export default function DevicesTab({ devices, setDevices, toggleBlock, hotspotActive, licenseData }) {
  const [search,   setSearch]   = useState('')
  const [toggling, setToggling] = useState(null)
  const [syncing,  setSyncing]  = useState(false)
  const [lastSync, setLastSync] = useState(null)

  // ── Parse netsh ARP table to detect real connected clients ──────────────────
  const parseArpClients = useCallback(async () => {
    try {
      const status = await window.electron.hotspot.status()
      if (!status.running) return []

      // Use netsh wlan show hostednetwork to get connected clients
      // The raw output contains client MAC addresses
      const raw = status.raw || ''
      const macRegex = /([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})/g
      const macs = [...new Set((raw.match(macRegex) || [])
        .map(m => m.toUpperCase())
        .filter(m => m !== 'FF:FF:FF:FF:FF:FF')
      )]
      return macs
    } catch {
      return []
    }
  }, [])

  // ── Sync devices with backend ───────────────────────────────────────────────
  const syncDevices = useCallback(async () => {
    setSyncing(true)
    try {
      // 1. Get current device list from backend
      const { data: backendDevices } = await api.get('/devices')
      setDevices(backendDevices)
      setLastSync(new Date())

      // 2. Try to detect real clients from netsh
      const detectedMacs = await parseArpClients()

      // 3. Report detected devices to backend (upsert)
      if (detectedMacs.length > 0) {
        const reports = detectedMacs.map(mac => ({
          macAddress: mac,
          deviceName: `Device ${mac.slice(-5)}`,
          deviceType: 'UNKNOWN',
        }))
        await api.post('/devices/report/bulk', { devices: reports })
        // Refresh after reporting
        const { data: updated } = await api.get('/devices')
        setDevices(updated)
      }
    } catch {}
    finally {
      setSyncing(false)
    }
  }, [parseArpClients, setDevices])

  // Auto-sync every 8 seconds when hotspot is active
  useEffect(() => {
    syncDevices()
    const id = setInterval(syncDevices, 8000)
    return () => clearInterval(id)
  }, [syncDevices])

  const handleToggle = async (id) => {
    setToggling(id)
    await toggleBlock(id)
    setToggling(null)
  }

  const filtered = devices.filter(d =>
    !search ||
    d.deviceName?.toLowerCase().includes(search.toLowerCase()) ||
    d.macAddress?.toLowerCase().includes(search.toLowerCase()) ||
    d.ipAddress?.includes(search)
  )

  const online  = devices.filter(d => d.online && !d.blocked).length
  const blocked = devices.filter(d => d.blocked).length
  const maxDevices = licenseData?.unlimitedDevices ? Infinity : (licenseData?.maxDevices ?? Infinity)
  const maxDisplay = licenseData?.unlimitedDevices ? '∞' : (licenseData?.maxDevices ?? '—')
  const limitReached = !licenseData?.unlimitedDevices && online >= maxDevices

  return (
    <div className="p-5 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-bold text-slate-900">Connected Devices</h2>
          <div className="flex items-center gap-2 mt-0.5">
            <div className={`w-1.5 h-1.5 rounded-full ${syncing ? 'bg-amber-400 animate-pulse' : 'bg-emerald-400'}`} />
            <p className="text-xs text-slate-400">
              {syncing ? 'Syncing...' : lastSync ? `Synced ${timeAgo(lastSync)}` : 'Auto-syncs every 8s'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {online > 0 && <span className="badge-green">{online}/{maxDisplay}</span>}
          {blocked > 0 && <span className="badge-red">{blocked} blocked</span>}
          <button onClick={syncDevices} className="btn-ghost">
            <RefreshCw className={`w-3.5 h-3.5 ${syncing ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      {/* Device limit warning */}
      {limitReached && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          className="bg-orange-50 border border-orange-200 rounded-2xl p-4 flex items-start gap-3"
        >
          <span className="text-2xl">⚠️</span>
          <div className="flex-1">
            <p className="text-sm font-bold text-orange-800">Device Limit Reached!</p>
            <p className="text-xs text-orange-600 mt-0.5">
              Your current plan allows only {maxDisplay} device(s). 
              The 4th device cannot connect. Upgrade your plan to allow more devices.
            </p>
          </div>
          <a href="https://wifi.vault-x.world/dashboard/plans" target="_blank"
            className="flex-shrink-0 bg-orange-500 text-white text-xs font-bold px-3 py-1.5 rounded-lg hover:bg-orange-600 transition-colors">
            Upgrade Plan
          </a>
        </motion.div>
      )}

      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-400" />
        <input
          className="input-field pl-9 py-2 text-sm"
          placeholder="Search by name, MAC or IP..."
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
      </div>

      {/* Empty states */}
      {!hotspotActive && devices.length === 0 ? (
        <div className="card py-12 text-center">
          <Wifi className="w-10 h-10 text-slate-200 mx-auto mb-3" />
          <p className="text-sm font-medium text-slate-500">Hotspot is not running</p>
          <p className="text-xs text-slate-400 mt-1">Start your hotspot to see connected devices</p>
        </div>
      ) : filtered.length === 0 ? (
        <div className="card py-12 text-center">
          <Monitor className="w-10 h-10 text-slate-200 mx-auto mb-3" />
          <p className="text-sm font-medium text-slate-500">
            {search ? 'No devices match your search' : 'No devices connected yet'}
          </p>
        </div>
      ) : (
        <div className="space-y-2">
          <AnimatePresence>
            {filtered.map((device, i) => (
              <motion.div
                key={device.id}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, x: -16 }}
                transition={{ delay: i * 0.04 }}
                className={`card p-3.5 transition-opacity ${device.blocked ? 'opacity-60' : ''}`}
              >
                <div className="flex items-center gap-3">
                  <DeviceIcon type={device.deviceType} blocked={device.blocked} online={device.online} />

                  {/* Info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <p className="text-sm font-semibold text-slate-800 truncate">
                        {device.deviceName || 'Unknown Device'}
                      </p>
                      {device.blocked && <span className="badge-red flex-shrink-0">Blocked</span>}
                      {!device.online && !device.blocked && <span className="badge-gray flex-shrink-0">Offline</span>}
                    </div>
                    <div className="flex items-center gap-3 mt-0.5 flex-wrap">
                      <span className="text-[10px] text-slate-400 font-mono">{device.macAddress}</span>
                      {device.ipAddress && (
                        <span className="text-[10px] text-slate-400 font-mono">{device.ipAddress}</span>
                      )}
                      {device.vendor && (
                        <span className="text-[10px] text-slate-400">{device.vendor}</span>
                      )}
                    </div>
                  </div>

                  {/* Signal */}
                  <SignalBars strength={device.signalStrength} />

                  {/* Bandwidth */}
                  {!device.blocked && (
                    <div className="text-right flex-shrink-0 hidden sm:block">
                      <div className="flex items-center gap-1 text-[10px] text-brand-600 font-semibold justify-end">
                        <ArrowDownRight className="w-3 h-3" />
                        {formatBytes(device.bytesReceived)}
                      </div>
                      <div className="flex items-center gap-1 text-[10px] text-cyan-500 font-semibold justify-end">
                        <ArrowUpRight className="w-3 h-3" />
                        {formatBytes(device.bytesSent)}
                      </div>
                    </div>
                  )}

                  {/* Connection time */}
                  <div className="text-right flex-shrink-0 hidden sm:block">
                    <div className="flex items-center gap-1 text-[10px] text-slate-400 justify-end">
                      <Clock className="w-3 h-3" />
                      {connectionDuration(device.connectedAt)}
                    </div>
                    <p className="text-[10px] text-slate-300 mt-0.5">{timeAgo(device.lastSeen)}</p>
                  </div>

                  {/* Block button */}
                  <motion.button
                    whileTap={{ scale: 0.92 }}
                    onClick={() => handleToggle(device.id)}
                    disabled={toggling === device.id}
                    className={`flex items-center gap-1 text-[11px] font-semibold px-2.5 py-1.5 rounded-lg transition-all flex-shrink-0 ${
                      device.blocked
                        ? 'bg-emerald-50 text-emerald-600 hover:bg-emerald-100'
                        : 'bg-red-50 text-red-500 hover:bg-red-100'
                    }`}
                  >
                    {toggling === device.id
                      ? <div className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin" />
                      : <Ban className="w-3 h-3" />
                    }
                    {device.blocked ? 'Unblock' : 'Block'}
                  </motion.button>
                </div>
              </motion.div>
            ))}
          </AnimatePresence>
        </div>
      )}
    </div>
  )
}
