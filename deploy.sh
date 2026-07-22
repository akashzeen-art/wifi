#!/bin/bash
# Deploy WiFi Extender on Ubuntu VPS
# Usage:
#   sudo ./deploy.sh 159.253.60.41
#   sudo ./deploy.sh 159.253.60.41 wifi.vault-x.world
#   sudo DOMAIN=wifi.vault-x.world ./deploy.sh 159.253.60.41
set -euo pipefail

SERVER_IP="${1:?Usage: sudo ./deploy.sh <SERVER_IP> [DOMAIN]}"
DOMAIN="${2:-${DOMAIN:-wifi.vault-x.world}}"
APP_DIR="/var/www/wifi"
JAVA_VERSION="21"
DB_PASSWORD="${DB_PASSWORD:-securepassword}"
JWT_SECRET="${APP_JWT_SECRET:-$(openssl rand -hex 32)}"

# Prefer HTTPS domain when SSL cert exists; otherwise HTTP domain + IP
if [[ -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" ]]; then
  FRONTEND_URL="https://${DOMAIN}"
  USE_SSL=1
else
  FRONTEND_URL="http://${DOMAIN}"
  USE_SSL=0
fi

CORS_ORIGINS="https://${DOMAIN},http://${DOMAIN},http://${SERVER_IP},https://${SERVER_IP},http://localhost:5173"

echo "=== WiFi Extender deploy ==="
echo "Domain:    ${DOMAIN}"
echo "Server IP: ${SERVER_IP}"
echo "Frontend:  ${FRONTEND_URL}"
echo "App dir:   ${APP_DIR}"

if [[ $EUID -ne 0 ]]; then
  echo "Run as root: sudo ./deploy.sh ${SERVER_IP} ${DOMAIN}"
  exit 1
fi

# ── Packages (first run only) ────────────────────────────────────────────────
if ! command -v nginx &>/dev/null; then
  apt-get update -y
  apt-get install -y nginx postgresql postgresql-contrib curl git certbot python3-certbot-nginx
fi

if ! command -v java &>/dev/null || ! java -version 2>&1 | grep -q "21"; then
  apt-get update -y
  apt-get install -y "openjdk-${JAVA_VERSION}-jdk"
fi
java -version

if ! command -v node &>/dev/null; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
node -v && npm -v

# ── PostgreSQL ───────────────────────────────────────────────────────────────
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='wifi_extender'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE wifi_extender;"
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='wifiuser'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE USER wifiuser WITH PASSWORD '${DB_PASSWORD}';"
sudo -u postgres psql -c "ALTER DATABASE wifi_extender OWNER TO wifiuser;" 2>/dev/null || true
sudo -u postgres psql -d wifi_extender -c "GRANT ALL ON SCHEMA public TO wifiuser;" 2>/dev/null || true
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE wifi_extender TO wifiuser;" 2>/dev/null || true

mkdir -p "${APP_DIR}/downloads"
# Copy desktop/Android installers into the NGINX downloads folder if present
cp -f "${APP_DIR}/frontend/public/downloads/"* "${APP_DIR}/downloads/" 2>/dev/null || true
cp -f "${APP_DIR}/frontend/dist/downloads/"* "${APP_DIR}/downloads/" 2>/dev/null || true
cp -f "${APP_DIR}/desktop-app/dist/"*.exe "${APP_DIR}/downloads/wifi-extender-setup.exe" 2>/dev/null || true
chown -R "${SUDO_USER:-www-data}:${SUDO_USER:-www-data}" "${APP_DIR}" 2>/dev/null || true
chmod -R a+rX "${APP_DIR}/downloads" 2>/dev/null || true

# ── Build frontend ───────────────────────────────────────────────────────────
cd "${APP_DIR}/frontend"
npm ci
npm run build

# ── Build backend ────────────────────────────────────────────────────────────
cd "${APP_DIR}/backend"
chmod +x mvnw
./mvnw clean package -DskipTests -q
JAR=$(ls target/wifi-extender-backend-*.jar | head -1)
cp "${JAR}" "${APP_DIR}/wifi-extender.jar"

# ── Systemd service ──────────────────────────────────────────────────────────
cat > /etc/systemd/system/wifi-extender.service << EOF
[Unit]
Description=WiFi Extender Backend
After=network.target postgresql.service

[Service]
Type=simple
User=www-data
Group=www-data
WorkingDirectory=${APP_DIR}
ExecStart=/usr/bin/java -jar ${APP_DIR}/wifi-extender.jar
Restart=on-failure
RestartSec=10
Environment="SERVER_PORT=8082"
Environment="SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/wifi_extender"
Environment="SPRING_DATASOURCE_USERNAME=wifiuser"
Environment="SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}"
Environment="APP_JWT_SECRET=${JWT_SECRET}"
Environment="APP_CORS_ALLOWED_ORIGINS=${CORS_ORIGINS}"
Environment="APP_FRONTEND_URL=${FRONTEND_URL}"

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable wifi-extender
systemctl restart wifi-extender

# ── NGINX ────────────────────────────────────────────────────────────────────
# Always keep HTTP (IP + domain) working
cp "${APP_DIR}/nginx/wifi-extender-ip.conf" /etc/nginx/sites-available/wifi-extender
# If SSL cert exists, use HTTPS domain config as primary
if [[ "${USE_SSL}" -eq 1 ]]; then
  cp "${APP_DIR}/nginx/wifi-extender.conf" /etc/nginx/sites-available/wifi-extender-ssl
  ln -sf /etc/nginx/sites-available/wifi-extender-ssl /etc/nginx/sites-enabled/wifi-extender-ssl
fi
ln -sf /etc/nginx/sites-available/wifi-extender /etc/nginx/sites-enabled/wifi-extender
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo ""
echo "=== Deployment complete ==="
echo "Domain:    https://${DOMAIN}  (or http://${DOMAIN} if SSL not issued yet)"
echo "IP:        http://${SERVER_IP}"
echo "API:       ${FRONTEND_URL}/api"
echo "Admin:     admin@wifiextender.com / admin123  (change after first login)"
echo ""
echo "If SSL is not set up yet, run:"
echo "  sudo certbot --nginx -d ${DOMAIN}"
echo "  sudo ./deploy.sh ${SERVER_IP} ${DOMAIN}"
echo ""
echo "Service:   systemctl status wifi-extender"
echo "Logs:      journalctl -u wifi-extender -f"
