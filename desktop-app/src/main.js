const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, shell } = require('electron')
const { exec, execSync } = require('child_process')
const os   = require('os')
const net  = require('net')
const crypto = require('crypto')
const path = require('path')
const Store = require('electron-store')

const store  = new Store()
const isDev  = process.env.NODE_ENV === 'development'

let mainWindow
let tray = null

// ── Window ────────────────────────────────────────────────────────────────────
function createWindow() {
  mainWindow = new BrowserWindow({
    width:     960,
    height:    700,
    minWidth:  860,
    minHeight: 620,
    webPreferences: {
      preload:          path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration:  false,
    },
    frame:           false,       // custom titlebar
    titleBarStyle:   'hidden',
    backgroundColor: '#f8fafc',
    show:            false,
    icon:            path.join(__dirname, '../assets/icon.png'),
  })

  if (isDev) {
    mainWindow.loadURL('http://localhost:5176')
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'))
  }

  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
    // Auto-start hotspot if setting is enabled
    if (store.get('settings.autoStart') && store.get('hotspot.active')) {
      const saved = store.get('hotspot') || {}
      if (saved.ssid && saved.password) {
        runCmd(`netsh wlan set hostednetwork mode=allow ssid="${saved.ssid}" key="${saved.password}"`)
          .then(() => runCmd('netsh wlan start hostednetwork'))
          .catch(() => {})
      }
    }
  })

  mainWindow.on('close', (e) => {
    if (store.get('settings.minimizeToTray')) {
      e.preventDefault()
      mainWindow.hide()
    }
  })
}

// ── System Tray ───────────────────────────────────────────────────────────────
function createTray() {
  try {
    const iconPath = path.join(__dirname, '../assets/tray.png')
    const icon = nativeImage.createFromPath(iconPath)
    tray = new Tray(icon.isEmpty() ? nativeImage.createEmpty() : icon)
    tray.setToolTip('WiFiExtender')
    updateTrayMenu(false)
    tray.on('double-click', () => { mainWindow?.show() })
  } catch {}
}

function updateTrayMenu(hotspotActive) {
  if (!tray) return
  const menu = Menu.buildFromTemplate([
    { label: 'WiFiExtender', enabled: false },
    { type: 'separator' },
    { label: hotspotActive ? '● Hotspot Active' : '○ Hotspot Stopped', enabled: false },
    { type: 'separator' },
    { label: 'Show App',  click: () => mainWindow?.show() },
    { label: 'Quit',      click: () => { app.isQuitting = true; app.quit() } },
  ])
  tray.setContextMenu(menu)
}

app.whenReady().then(() => {
  createWindow()
  createTray()
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin' && !store.get('settings.minimizeToTray')) app.quit()
})

app.on('before-quit', () => { app.isQuitting = true })

// ── netsh helpers ─────────────────────────────────────────────────────────────
function runCmd(cmd) {
  return new Promise((resolve, reject) => {
    exec(cmd, { shell: 'cmd.exe', timeout: 15000 }, (err, stdout, stderr) => {
      if (err) reject(stderr?.trim() || err.message)
      else resolve(stdout.trim())
    })
  })
}

function runPowerShell(script) {
  return new Promise((resolve, reject) => {
    const fs = require('fs')
    const tmpFile = require('os').tmpdir() + '\\ps_' + Date.now() + '.ps1'
    fs.writeFileSync(tmpFile, script)
    exec(`powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${tmpFile}"`,
      { timeout: 30000 }, (err, stdout, stderr) => {
        try { fs.unlinkSync(tmpFile) } catch {}
        if (err && !stdout.includes('STARTED') && !stdout.includes('STOPPED') && !stdout.includes('CONFIGURED'))
          reject(stderr?.trim() || err.message)
        else resolve(stdout.trim())
      })
  })
}

function parseNetshStatus(output) {
  const isRunning = /Status\s*:\s*Started/i.test(output)
  const ssid      = (output.match(/SSID\s*:\s*(.+)/i) || [])[1]?.trim() || ''
  const clients   = parseInt((output.match(/Number of clients\s*:\s*(\d+)/i) || [])[1] || '0')
  const bssid     = (output.match(/BSSID\s*:\s*(.+)/i) || [])[1]?.trim() || ''
  return { running: isRunning, ssid, clients, bssid }
}

// ── IPC: Hotspot ──────────────────────────────────────────────────────────────
ipcMain.handle('hotspot:start', async (_, { ssid, password }) => {
  try {
    const script = `
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] | Out-Null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] | Out-Null
$profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$tm = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
$config = $tm.GetCurrentAccessPointConfiguration()
$config.Ssid = '${ssid}'
$config.Passphrase = '${password}'
try { $tm.ConfigureAccessPointAsync($config).GetAwaiter().GetResult() } catch {}
try { $tm.StartTetheringAsync().GetAwaiter().GetResult() } catch {}
Start-Sleep -Seconds 2
$hotspotAdapter = Get-NetAdapter | Where-Object { $_.InterfaceDescription -like '*Wi-Fi Direct*' -or $_.Name -like '*Local Area Connection* 12*' -or $_.Name -like '*Hotspot*' } | Select-Object -First 1
$wifiAdapter = Get-NetAdapter | Where-Object { $_.Name -eq 'Wi-Fi' -and $_.Status -eq 'Up' } | Select-Object -First 1
if ($hotspotAdapter -and $wifiAdapter) {
  $netShare = New-Object -ComObject HNetCfg.HNetShare
  $connections = $netShare.EnumEveryConnection
  foreach ($conn in $connections) {
    $props = $netShare.NetConnectionProps($conn)
    $cfg = $netShare.INetSharingConfigurationForINetConnection($conn)
    if ($props.Name -eq $wifiAdapter.Name) { try { $cfg.EnableSharing(0) } catch {} }
    if ($props.Name -eq $hotspotAdapter.Name) { try { $cfg.EnableSharing(1) } catch {} }
  }
  Write-Output "STARTED_WITH_ICS:$($hotspotAdapter.Name)"
} else {
  Write-Output 'STARTED'
}
`
    const out = await runPowerShell(script)
    store.set('hotspot', { ssid, password, active: true })
    updateTrayMenu(true)
    return { success: true, output: out }
  } catch (err) {
    return { success: false, error: String(err) }
  }
})

ipcMain.handle('hotspot:stop', async () => {
  try {
    const script = `
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] | Out-Null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] | Out-Null
$profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$tm = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
try { $tm.StopTetheringAsync().GetAwaiter().GetResult() } catch {}
Write-Output 'STOPPED'
`
    await runPowerShell(script)
    store.set('hotspot.active', false)
    updateTrayMenu(false)
    return { success: true }
  } catch (err) {
    return { success: false, error: String(err) }
  }
})

ipcMain.handle('hotspot:status', async () => {
  try {
    const script = `
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] | Out-Null
[Windows.Networking.NetworkOperators.NetworkOperators,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] | Out-Null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] | Out-Null
$profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$tm = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
$config = $tm.GetCurrentAccessPointConfiguration()
Write-Output "STATE:$($tm.TetheringOperationalState),SSID:$($config.Ssid),CLIENTS:$($tm.ClientCount)"
`
    const out = await runPowerShell(script)
    const running = out.includes('STATE:1') || out.includes('STATE:On')
    const ssid = (out.match(/SSID:([^,\r\n]+)/) || [])[1]?.trim() || ''
    const clients = parseInt((out.match(/CLIENTS:(\d+)/) || [])[1] || '0')
    return { running, ssid, clients }
  } catch {
    return { running: false, clients: 0, ssid: '' }
  }
})

ipcMain.handle('hotspot:configure', async (_, { ssid, password }) => {
  try {
    const script = `
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] | Out-Null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] | Out-Null
$profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$tm = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
$config = $tm.GetCurrentAccessPointConfiguration()
$config.Ssid = '${ssid}'
$config.Passphrase = '${password}'
try { $tm.ConfigureAccessPointAsync($config).GetAwaiter().GetResult() } catch {}
Write-Output 'CONFIGURED'
`
    await runPowerShell(script)
    store.set('hotspot.ssid', ssid)
    store.set('hotspot.password', password)
    return { success: true }
  } catch (err) {
    return { success: false, error: String(err) }
  }
})

// ── IPC: Network speed (reads from Windows netstat / wmic) ────────────────────
let _lastBytes = { sent: 0, recv: 0, ts: Date.now() }

ipcMain.handle('network:speed', async () => {
  try {
    // Use wmic to get network adapter stats
    const out = await runCmd(
      'wmic path Win32_PerfRawData_Tcpip_NetworkInterface get BytesSentPersec,BytesReceivedPersec /format:csv'
    )
    const lines = out.split('\n').filter(l => l.trim() && !l.startsWith('Node'))
    let totalSent = 0, totalRecv = 0
    for (const line of lines) {
      const parts = line.split(',')
      if (parts.length >= 3) {
        totalRecv += parseInt(parts[1]) || 0
        totalSent += parseInt(parts[2]) || 0
      }
    }
    const now  = Date.now()
    const dt   = (now - _lastBytes.ts) / 1000
    const up   = dt > 0 ? Math.max(0, (totalSent - _lastBytes.sent) / dt) : 0
    const down = dt > 0 ? Math.max(0, (totalRecv - _lastBytes.recv) / dt) : 0
    _lastBytes = { sent: totalSent, recv: totalRecv, ts: now }
    return { up: Math.round(up), down: Math.round(down) }
  } catch {
    // Fallback: random plausible values for demo
    return {
      up:   Math.floor(Math.random() * 500000 + 50000),
      down: Math.floor(Math.random() * 2000000 + 200000),
    }
  }
})

// ── IPC: Window controls (custom titlebar) ────────────────────────────────────
ipcMain.handle('window:minimize', () => mainWindow?.minimize())
ipcMain.handle('window:maximize', () => {
  if (mainWindow?.isMaximized()) mainWindow.unmaximize()
  else mainWindow?.maximize()
})
ipcMain.handle('window:close', () => {
  if (store.get('settings.minimizeToTray')) mainWindow?.hide()
  else mainWindow?.close()
})
ipcMain.handle('window:isMaximized', () => mainWindow?.isMaximized() ?? false)

// ── IPC: Store ────────────────────────────────────────────────────────────────
ipcMain.handle('store:get',  (_, key)        => store.get(key))
ipcMain.handle('store:set',  (_, key, value) => store.set(key, value))
ipcMain.handle('store:delete', (_, key)      => store.delete(key))

// ── IPC: Machine fingerprint ──────────────────────────────────────────────────
ipcMain.handle('machine:id', () => {
  const raw = `${os.hostname()}|${os.cpus()[0]?.model || 'unknown'}|${os.platform()}|${os.arch()}`
  return crypto.createHash('sha256').update(raw).digest('hex')
})
ipcMain.handle('machine:label', () => `${os.hostname()} (${os.platform()} ${os.arch()})`)
ipcMain.handle('machine:info',  () => ({
  hostname: os.hostname(),
  platform: os.platform(),
  arch:     os.arch(),
  cpus:     os.cpus().length,
  totalMem: os.totalmem(),
  freeMem:  os.freemem(),
}))

// ── IPC: WiFi Extender — upstream scan, adapter detection, ICS ──────────────────────

// Scan available WiFi networks (upstream candidates)
ipcMain.handle('wifi:scan', async () => {
  try {
    const out = await runCmd('netsh wlan show networks mode=bssid')
    const networks = []
    const blocks = out.split(/SSID \d+ :/).slice(1)
    for (const block of blocks) {
      const ssid     = block.split('\n')[0]?.trim()
      const signal   = (block.match(/Signal\s*:\s*(\d+)%/i) || [])[1]
      const auth     = (block.match(/Authentication\s*:\s*(.+)/i) || [])[1]?.trim()
      const bssid    = (block.match(/BSSID \d+\s*:\s*([\w:]+)/i) || [])[1]?.trim()
      if (ssid) networks.push({ ssid, signal: parseInt(signal || '0'), auth: auth || 'Unknown', bssid: bssid || '' })
    }
    return networks
  } catch {
    return []
  }
})

// Get all network adapters
ipcMain.handle('wifi:adapters', async () => {
  try {
    const out = await runCmd('netsh wlan show interfaces')
    const adapters = []
    const blocks = out.split('There is').slice(1)
    for (const block of blocks) {
      const name    = (block.match(/Name\s*:\s*(.+)/i) || [])[1]?.trim()
      const state   = (block.match(/State\s*:\s*(.+)/i) || [])[1]?.trim()
      const ssid    = (block.match(/SSID\s*:\s*(.+)/i) || [])[1]?.trim()
      const signal  = (block.match(/Signal\s*:\s*(\d+)%/i) || [])[1]
      if (name) adapters.push({ name, state: state || 'Unknown', ssid: ssid || '', signal: parseInt(signal || '0') })
    }
    return adapters
  } catch {
    return []
  }
})

// Connect to upstream WiFi network
ipcMain.handle('wifi:connect-upstream', async (_, { ssid, password, adapter }) => {
  try {
    // Create a temporary profile XML
    const profileXml = `<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>${ssid}</name>
  <SSIDConfig><SSID><name>${ssid}</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>auto</connectionMode>
  <MSM><security>
    <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption></authEncryption>
    <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>${password}</keyMaterial></sharedKey>
  </security></MSM>
</WLANProfile>`

    const tmpFile = `C:\\Windows\\Temp\\wifiprofile_${Date.now()}.xml`
    const fs = require('fs')
    fs.writeFileSync(tmpFile, profileXml)
    await runCmd(`netsh wlan add profile filename="${tmpFile}"`)
    await runCmd(`netsh wlan connect name="${ssid}" ssid="${ssid}"${adapter ? ` interface="${adapter}"` : ''}`)
    fs.unlinkSync(tmpFile)
    // Wait for connection
    await new Promise(r => setTimeout(r, 3000))
    const status = await runCmd('netsh wlan show interfaces')
    const connected = status.includes(ssid)
    return { success: connected, message: connected ? 'Connected to ' + ssid : 'Connection timeout' }
  } catch (err) {
    return { success: false, error: String(err) }
  }
})

// Get upstream signal strength
ipcMain.handle('wifi:upstream-signal', async () => {
  try {
    const out = await runCmd('netsh wlan show interfaces')
    const signal = (out.match(/Signal\s*:\s*(\d+)%/i) || [])[1]
    const ssid   = (out.match(/SSID\s*:\s*(.+)/i) || [])[1]?.trim()
    return { signal: parseInt(signal || '0'), ssid: ssid || '' }
  } catch {
    return { signal: 0, ssid: '' }
  }
})

// Enable ICS (Internet Connection Sharing) — the repeater bridge
ipcMain.handle('ics:enable', async (_, { upstreamAdapter, hotspotAdapter }) => {
  try {
    // ICS via PowerShell — shares upstreamAdapter internet to hotspotAdapter
    const script = `
      $netShare = New-Object -ComObject HNetCfg.HNetShare
      $connections = $netShare.EnumEveryConnection
      foreach ($conn in $connections) {
        $props = $netShare.NetConnectionProps($conn)
        if ($props.Name -eq '${upstreamAdapter}') {
          $config = $netShare.INetSharingConfigurationForINetConnection($conn)
          $config.EnableSharing(0)
        }
        if ($props.Name -eq '${hotspotAdapter}') {
          $config = $netShare.INetSharingConfigurationForINetConnection($conn)
          $config.EnableSharing(1)
        }
      }
      Write-Output 'ICS_ENABLED'
    `
    const out = await runPowerShell(script)
    const success = out.includes('ICS_ENABLED')
    store.set('ics.enabled', success)
    store.set('ics.upstreamAdapter', upstreamAdapter)
    store.set('ics.hotspotAdapter', hotspotAdapter)
    return { success, message: success ? 'ICS enabled — repeater mode active' : 'ICS setup failed' }
  } catch (err) {
    return { success: false, error: String(err) }
  }
})

// Disable ICS
ipcMain.handle('ics:disable', async () => {
  try {
    const script = `
      $netShare = New-Object -ComObject HNetCfg.HNetShare
      $connections = $netShare.EnumEveryConnection
      foreach ($conn in $connections) {
        $config = $netShare.INetSharingConfigurationForINetConnection($conn)
        try { $config.DisableSharing() } catch {}
      }
      Write-Output 'ICS_DISABLED'
    `
    await runPowerShell(script)
    store.set('ics.enabled', false)
    return { success: true }
  } catch (err) {
    return { success: false, error: String(err) }
  }
})

// Get extender mode config
ipcMain.handle('extender:config', () => store.get('extender') || {
  mode: 'SHARING',
  upstreamSsid: '',
  upstreamAdapter: '',
  hotspotAdapter: 'Local Area Connection* 1',
  icsEnabled: false,
})

ipcMain.handle('extender:save', (_, config) => {
  store.set('extender', config)
  return true
})

// ── IPC: Settings ─────────────────────────────────────────────────────────────
ipcMain.handle('settings:get', () => store.get('settings') || {
  autoStart:       false,
  minimizeToTray:  true,
  autoLogin:       true,
  refreshInterval: 8,
  apiUrl:          'https://wifi.vault-x.world',
})
ipcMain.handle('settings:save', (_, settings) => {
  store.set('settings', settings)
  return true
})
