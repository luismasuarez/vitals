# Probar en el móvil

Estado: app de producción `0.1.0` instalada en el Galaxy A54 (release, firmada,
sólo `arm64-v8a`). Falta levantar el agente y emparejar.

## 1. Levantar el agente

### Opción A — servicio systemd (recomendado)

```bash
cd ~/tools/vitals
curl -LO https://github.com/luismasuarez/vitals/releases/download/v0.1.0/vitals-0.1.0-x86_64-unknown-linux-musl.tar.gz
tar xzf vitals-0.1.0-x86_64-unknown-linux-musl.tar.gz

sudo install -m 0755 vitals-0.1.0-x86_64-unknown-linux-musl/vitals /usr/local/bin/vitals
sudo install -d /etc/vitals
sudo install -m 0644 vitals-0.1.0-x86_64-unknown-linux-musl/config.toml.example /etc/vitals/config.toml
sudo install -m 0644 vitals-0.1.0-x86_64-unknown-linux-musl/vitals.service /etc/systemd/system/vitals.service
sudo systemctl daemon-reload
sudo systemctl enable --now vitals
journalctl -u vitals -n 20
```

Comprueba en el log los endpoints en los que escucha: deben ser las IPs de LAN,
la de `tailscale0` y loopback. Nunca `0.0.0.0`.

### Opción B — sin sudo, prueba rápida

```bash
cd ~/tools/vitals
cargo build --release
STATE=~/.local/state/vitals
mkdir -p "$STATE"
./target/release/vitals --state-dir "$STATE" serve
```

Deja esa terminal abierta. En otra, los comandos de administración llevan el
mismo `--state-dir`.

## 2. Emparejar con el QR

```bash
sudo vitals pair --name "Galaxy A54"          # opción A
./target/release/vitals --state-dir "$STATE" pair --name "Galaxy A54"   # opción B
```

Imprime un QR en la terminal y, debajo, la misma carga en texto
(`vitals1:…`). El código es de un solo uso y caduca en 5 minutos.

En el móvil:

1. Abre **vitals**.
2. Toca **Escanear QR** y concede el permiso de cámara.
3. Apunta al QR de la terminal.
4. La app muestra host, endpoints, pin y caducidad → **Emparejar**.

Si el QR no se lee (fuente, zoom o terminal con fondo claro):

- Toca **Pegar el código a mano** y pega la línea `vitals1:…` que aparece bajo
  el QR.
- Genera otro código: cada uno se consume al usarse o al caducar.

## 3. Verificar

- **Inicio**: nombre del host, anillo de CPU, memoria, tráfico, discos.
- **Píldora de la cabecera**: `LAN` con punto verde = stream SSE activo;
  ámbar = polling de respaldo.
- **Red → Conexión**: endpoints con el activo marcado; tocar otro fuerza el
  cambio.
- **Ajustes** (engranaje): pin, token enmascarado, dispositivos emparejados.

Fuera de casa, activa Tailscale en el móvil: el failover pasa al endpoint
`100.x` sin cambiar nada en la app.

## 4. Operación

```bash
sudo vitals devices list
sudo vitals devices revoke <device_id>
sudo vitals tls show
```

Rotar el certificado (obliga a re-emparejar):

```bash
sudo systemctl stop vitals
sudo rm /var/lib/vitals/tls-cert.pem /var/lib/vitals/tls-key.pem
sudo systemctl start vitals
```

## 5. Actualizar la app

Descarga `vitals-<version>-android.apk` de la página de releases e instálala
encima (misma clave de firma, los datos se conservan):

```bash
adb install -r vitals-0.1.0-android.apk
```

La APK es sólo `arm64-v8a`. El keystore de firma vive en
`~/keystores/vitals-release.jks` (copia de seguridad fuera del repo) y sus
cuatro secrets ya están configurados en GitHub.

## 6. Si algo falla

| Síntoma | Revisar |
|---|---|
| La app no conecta en LAN | `journalctl -u vitals`, puerto 8443, firewall del host |
| Conecta pero sin datos | misma red Wi-Fi, IP en el QR correcta |
| `pin mismatch` | el certificado se rotó: re-empareja |
| 401 en Ajustes | token revocado en el host: re-empareja |
| QR no se lee | usa el pegado manual |

El repositorio es privado. Para descargas públicas:
`gh repo edit --visibility public`.
