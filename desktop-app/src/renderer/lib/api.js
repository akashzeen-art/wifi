import axios from 'axios'

export const DEFAULT_API_URL = 'https://wifi.vault-x.world'

const normalizeBase = (url) => {
  const raw = (url || DEFAULT_API_URL).trim().replace(/\/+$/, '')
  return raw.endsWith('/api') ? raw : `${raw}/api`
}

const getBase = () => {
  try {
    return normalizeBase(window.__apiUrl || DEFAULT_API_URL)
  } catch {
    return normalizeBase(DEFAULT_API_URL)
  }
}

const api = axios.create({
  baseURL: normalizeBase(DEFAULT_API_URL),
  timeout: 20000,
})

api.interceptors.request.use(config => {
  config.baseURL = getBase()
  const token = window.__token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export default api
