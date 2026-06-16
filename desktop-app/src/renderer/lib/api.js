import axios from 'axios'

const getBase = () => {
  try {
    const stored = window.__apiUrl
    return (stored || 'http://localhost:8080') + '/api'
  } catch {
    return 'http://localhost:8080/api'
  }
}

const api = axios.create({ baseURL: 'http://localhost:8080/api' })

api.interceptors.request.use(config => {
  config.baseURL = getBase()
  const token = window.__token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export default api
