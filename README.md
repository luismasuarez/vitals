# vitals

Agente de métricas del host, de sólo lectura, sobre HTTPS con certificado
propio y pinning. Pensado para un cliente Android que consulta CPU, memoria,
discos, red, temperaturas y procesos de un único host, tanto en la red local
como a través de Tailscale.

Un binario, sin runtime, sin dependencias del sistema (SQLite compilado dentro),
sin endpoints de escritura y sin acceso a internet.

```
Android ──HTTPS + token──► vitals (axum + sysinfo)
                              ├── SQLite (histórico 10 s / 1 min)
                              └── /run/vitals/control.sock (pairing, revocación)
```

## Modelo de seguridad

| Capa | Decisión |
|---|---|
| Transporte | TLS 1.3/1.2 con certificado autofirmado generado en el primer arranque |
| Confianza | El cliente fija el **pin** = SHA-256 del certificado (16 bytes, base64url) recibido en el QR. No hay CA ni validación de hostname |
| Identidad | Token por dispositivo (`<device_id>.<secret>`), guardado como SHA-256 en SQLite, comparado en tiempo constante |
| Pairing | Código de un solo uso, 40 bits, TTL 5 min, entregado por socket unix local. El token **no** viaja en el QR |
| Red | Bind explícito por dirección: loopback, Tailscale y RFC1918. Nunca `0.0.0.0` |
| Sandbox | `DynamicUser`, `ProtectSystem=strict`, sin capabilities, `IPAddressDeny=any` como red de seguridad de egreso |
| Superficie | Sólo `GET`; la única excepción es `POST /api/v1/pair` |

Consecuencias prácticas:

- Un atacante en la LAN que haga ARP spoofing no puede leer el token (TLS) ni
  suplantar el servidor (pin).
- Comprometer un nodo del tailnet no da acceso: hace falta además un token.
- En un VPS el puerto no existe en la interfaz pública, sólo en `tailscale0`.

## Instalación en una línea

```bash
curl -fsSL https://raw.githubusercontent.com/luismasuarez/vitals/main/scripts/install.sh | bash
vitals pair --name "mi-telefono"
```

Sin `sudo` instala en `~/.local/bin` y registra un servicio de usuario; con
`sudo` instala en `/usr/local/bin` y usa el servicio del sistema.

El mismo comando es el actualizador: si ya estás en la última versión es un
no-op, si hay una nueva reemplaza el binario y reinicia el servicio sin tocar
configuración ni datos. Para no recordar la URL queda instalado
`vitals-update` (acepta `--check`, `--version X.Y.Z`, `--force`).

## Compilar

```bash
cargo build --release
ls -lh target/release/vitals     # ~5-7 MB
```

Cross-compile estático para un VPS (musl):

```bash
rustup target add x86_64-unknown-linux-musl
cargo build --release --target x86_64-unknown-linux-musl
```

## Instalar como servicio

```bash
sudo install -m 0755 target/release/vitals /usr/local/bin/vitals
sudo install -d /etc/vitals
sudo install -m 0644 deploy/config.toml.example /etc/vitals/config.toml
sudo install -m 0644 deploy/vitals.service /etc/systemd/system/vitals.service
sudo systemctl daemon-reload
sudo systemctl enable --now vitals
journalctl -u vitals -f
```

El arranque registra los endpoints y crea `/var/lib/vitals` (base de datos y
certificado) y `/run/vitals/control.sock`.

## Emparejar el móvil

```bash
sudo vitals pair --name pixel
```

Imprime un QR y, debajo, el mismo payload en texto. El QR contiene:

```json
{
  "v": 1,
  "name": "steampunker",
  "ep": ["https://192.168.1.104:8443", "https://100.71.72.48:8443"],
  "pin": "RZPKDkL9mRhkkObOA54Hpw",
  "code": "8AYG-P5P0",
  "exp": 1795471954
}
```

Codificado como `vitals1:<base64url>`. La app debe:

1. Guardar `ep` como lista ordenada de endpoints (LAN primero, Tailscale después).
2. Guardar `pin` como única autoridad de confianza.
3. Hacer `POST /api/v1/pair` con `{"code": "..."}` sobre la conexión ya fijada.
4. Guardar el `token` de la respuesta en el Keystore y descartar el código.

El código caduca en 5 minutos y se consume en el primer uso. Si la app falla a
mitad de camino, basta con emitir otro.

## Operación

```bash
sudo vitals devices list
sudo vitals devices revoke <device_id>
sudo vitals tls show          # pin, endpoints y número de dispositivos
```

### Rotar el certificado

El pin cambia, así que todos los dispositivos deben re-emparejarse:

```bash
sudo systemctl stop vitals
sudo rm /var/lib/vitals/tls-cert.pem /var/lib/vitals/tls-key.pem
sudo systemctl start vitals
sudo vitals pair --name pixel
```

Los tokens sobreviven a la rotación; lo que caduca es el pin. Revoca los
dispositivos que ya no uses.

## App Android

La app nativa vive en `app/` (Kotlin + Compose) y replica la interfaz del
mockup hablando la API real:

- **Pinning**: `X509TrustManager` propio que compara `sha256(cert.encoded)[..16]`
  con el `pin` del QR. El `HostnameVerifier` acepta el certificado ya validado:
  el pin es la raíz de confianza y un cambio de IP por DHCP no rompe nada.
- **Failover**: recorre los endpoints del QR en cada request y recuerda el que
  funcionó; el topbar muestra `LAN`, `Tailscale` y el estado del transporte.
- **Tiempo real**: SSE con OkHttp; si el stream se cae, polling cada 2,5 s y
  reconexión con backoff.
- **Emparejamiento**: cámara con CameraX + ML Kit, con dos alternativas para
  cuando no hay cámara o permiso: pegar el payload o `-e pair "vitals1:…"`.

```bash
cd app && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/vitals-app-debug.apk
```

Detalles de arquitectura, firma y limitaciones en [`app/README.md`](app/README.md).

> `CertificatePinner` de OkHttp no sirve aquí: fija hashes SPKI, no el
> certificado completo. Con certificado autofirmado el camino correcto es el
> `TrustManager` del pin.

## Mockup web

`mockup/web` es el prototipo con el que se validó la UI antes de escribir
Kotlin. Sigue sirviendo para probar el agente desde el navegador: publica la web
en la LAN y hace de proxy con verificación de pin.

```bash
python3 mockup/serve.py --port 8080
# la cámara del móvil exige HTTPS:
tailscale serve --bg http://127.0.0.1:8080
```

## Verificación

```bash
cargo test                  # 13 tests: red, auth, store, rangos
bash scripts/smoke.sh       # end-to-end contra un puerto y estado temporales
cd app && ./gradlew assembleDebug
```

El smoke test cubre: handshake con `--cacert`, 401 sin token, pairing por
socket, intercambio de código, métricas, histórico, procesos, SSE, reutilización
de código y revocación. La app se probó en un Galaxy A54 contra el agente real.

## Limitaciones conocidas

- IPv4 por defecto. `bind_ipv6 = true` en la configuración añade IPv6.
- Un solo host por sesión. Varios hosts implican varias sesiones, no
  implementado todavía.
- El token de Android está en SharedPreferences; en producción corresponde al
  Keystore.
- Sin notificaciones push ni alertas: la app consulta, el servidor no avisa.
- Sin compresión ni paginación: los payloads son pequeños a propósito.
- `IPAddressDeny=any` en la unit impide egreso y también `bind()` a direcciones
  públicas, así que `allow_public = true` exige ampliar esa lista.

## Estado del proyecto

Hecho y probado: servidor (fases 1–3), app Android, mockup web y pipeline de
release con changelog generado por conventional commits.

## Probar en el móvil

Guía paso a paso (servicio, QR, verificación y actualización) en
[`docs/TESTING.md`](docs/TESTING.md).

## Instalación desde GitHub Releases

Cada etiqueta `v*` publica los binarios del servidor y el APK en la página de
releases del repositorio. Los nombres de archivo siguen este esquema:

| Plataforma | Archivo |
|---|---|
| Linux x86_64 (musl) | `vitals-<versión>-x86_64-unknown-linux-musl.tar.gz` |
| Linux aarch64 (musl) | `vitals-<versión>-aarch64-unknown-linux-musl.tar.gz` |
| Android | `vitals-<versión>-android.apk` |

> **Nota:** los ejemplos usan `TU_USUARIO/vitals`. Sustituye ese valor por el
> repositorio real antes de copiar los comandos. En ARM64 reemplaza `x86_64`
> por `aarch64` en los nombres de archivo.

Junto a los artefactos se publica `SHA256SUMS`. Verifica las descargas antes de
instalarlas:

```bash
sha256sum -c SHA256SUMS
```

### Servidor

```bash
# 1. Descarga y extrae el archivo de la versión
curl -L https://github.com/TU_USUARIO/vitals/releases/download/v0.2.0/vitals-0.2.0-x86_64-unknown-linux-musl.tar.gz | tar xz

# 2. Instala el binario, la unit de systemd y la configuración de ejemplo
sudo install -m 0755 vitals-0.2.0-x86_64-unknown-linux-musl/vitals /usr/local/bin/vitals && sudo install -d /etc/vitals && sudo install -m 0644 vitals-0.2.0-x86_64-unknown-linux-musl/config.toml.example /etc/vitals/config.toml && sudo install -m 0644 vitals-0.2.0-x86_64-unknown-linux-musl/vitals.service /etc/systemd/system/vitals.service

# 3. Arranca el servicio
sudo systemctl daemon-reload && sudo systemctl enable --now vitals
```

El servidor escucha en el puerto **8443** con el certificado autofirmado que
genera en el primer arranque. Después, empareja el móvil con
`sudo vitals pair --name pixel` y sigue el flujo del QR descrito arriba.

### APK de Android

Con el móvil conectado por `adb`:

```bash
adb install -r vitals-0.2.0-android.apk
```

También puedes descargar el APK desde la página de releases y abrirlo en el
móvil; el sistema pedirá permitir la instalación desde orígenes desconocidos.
El APK se actualiza sobre la instalación anterior siempre que esté firmado con
la misma clave.

### Firma del APK

Los APKs publicados se firman en CI con un keystore que nunca se sube al
repositorio. Para generarlo:

```bash
keytool -genkeypair -v \
  -keystore vitals-release.jks \
  -alias vitals \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS \
  -dname "CN=vitals, O=vitals"
```

Guarda el `.jks` y sus contraseñas en un lugar seguro: sin esa clave no se
pueden publicar actualizaciones que se instalen encima de la versión anterior.
Configura estos secrets en el repositorio (`Settings → Secrets and variables →
Actions`):

| Secret | Contenido |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Keystore codificado en base64, sin saltos de línea |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clave, `vitals` en el ejemplo |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave |

El valor del primer secret se obtiene con:

```bash
base64 -w0 vitals-release.jks
```

Si faltan esos secrets, el workflow compila un APK *debug* y lo publica como
`vitals-<versión>-android-debug.apk`, con un aviso en las notas del release de
que no está firmado para producción.
