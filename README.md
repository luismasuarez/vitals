# vitals

Agente de métricas del host sobre HTTPS con certificado propio y pinning.
Pensado para un cliente Android que consulta CPU, memoria, discos, red,
temperaturas, procesos, sesiones y accesos SSH y, en portátiles, batería y
consumo de un único host, tanto en la red local como a través de Tailscale.

Un binario, sin runtime, sin dependencias del sistema (SQLite compilado dentro).
La lectura es libre para cualquier token; la única escritura pasa por un
endpoint cerrado, auditado y con permisos por dispositivo.

```
Android ──HTTPS + token──► vitals (axum + sysinfo)
                              ├── SQLite (histórico, auditoría, concesiones)
                              └── /run/vitals/control.sock (pairing, caps, revocación)
```

## Modelo de seguridad

| Capa | Decisión |
|---|---|
| Transporte | TLS 1.3/1.2 con certificado autofirmado generado en el primer arranque |
| Confianza | El cliente fija el **pin** = SHA-256 del certificado (16 bytes, base64url) recibido en el QR. No hay CA ni validación de hostname |
| Identidad | Token por dispositivo (`<device_id>.<secret>`) con scope `reader` u `operator`, guardado como SHA-256 en SQLite y comparado en tiempo constante |
| Pairing | Código de un solo uso, 40 bits, TTL 5 min, entregado por socket unix local. El token **no** viaja en el QR |
| Red | Bind explícito por dirección: loopback, Tailscale y RFC1918. Nunca `0.0.0.0` |
| Sandbox | `DynamicUser`, `ProtectSystem=strict`, sin capabilities, `IPAddressDeny=any` como red de seguridad de egreso |
| Superficie | `GET` en todo; sólo `POST /api/v1/pair` y `POST /api/v1/command` cambian de método |
| Mutación | `command` con esquema cerrado, ≤4 KB, 60/min por dispositivo, `id` idempotente 24 h y auditoría de todos los intentos |
| Privilegio alto | `exec.*` no lo concede el scope: exige una concesión por dispositivo en `/etc/vitals/capabilities.toml`, escrita sólo por `vitals caps` |

Consecuencias prácticas:

- Un atacante en la LAN que haga ARP spoofing no puede leer el token (TLS) ni
  suplantar el servidor (pin).
- Comprometer un nodo del tailnet no da acceso: hace falta además un token.
- Comprometer un token `operator` no ejecuta nada: `exec.*` sigue necesitando
  una fila del manifiesto, y en 0.2 ni siquiera se ejecuta.
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
sudo vitals pair --name pixel              # scope reader
sudo vitals pair --operator --name pixel   # + command y auditoría
```

Imprime un QR y, debajo, el mismo payload en texto. El QR contiene:

```json
{
  "v": 1,
  "name": "steampunker",
  "dev": "pixel",
  "scope": "reader",
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
4. Guardar el `token` y el `scope` de la respuesta en el Keystore y descartar
   el código.

El scope real lo decide el código emitido en el host: editarlo en el QR no
eleva privilegios. El código caduca en 5 minutos y se consume en el primer uso.

## Operación

```bash
sudo vitals devices list
sudo vitals devices revoke <device_id>
sudo vitals tls show          # pin, endpoints, manifest y número de dispositivos

sudo vitals caps pending      # solicitudes de capability esperando aprobación
sudo vitals caps approve 1    # por número de la lista
sudo vitals caps approve 7KQ2M9
sudo vitals caps list
sudo vitals caps revoke <device_id> exec.unit_restart
```

### Alertas

El agente evalúa reglas de disco, memoria y temperatura en cada muestra, con
histéresis de 2 unidades, y guarda un historial de 30 días. Las reglas viajan
por `alerts.set` y el historial se lee con `GET /api/v1/alerts`; ambos son
`operator`. La predicción de «días restantes» sale de la regresión del uso de
disco de los últimos 7 días (una serie plana no alerta). El push y el aviso
local llegan con el watchdog de la app.

### Portátil

En un portátil, `/metrics` añade un objeto `power` con el porcentaje, el estado,
la potencia instantánea, el tiempo restante, la salud y los ciclos, leídos de
`/sys/class/power_supply`. `/host` indica `form_factor: "laptop"` y el historial
guarda `battery_pct` y `power_w`. En servidores y VPS el objeto no aparece y la
app oculta la tarjeta, así que el contrato sigue siendo el mismo. No hay
detección de suspensión ni alerta de batería baja todavía.

### Accesos

`/metrics` incluye un objeto `access` con las sesiones activas, los accesos
aceptados y los intentos fallidos de las últimas 24 h, más el estado de
`PasswordAuthentication` y `PermitRootLogin`. `GET /api/v1/access?range=7d`
devuelve el detalle por evento, el resumen por dirección de origen y la
cobertura real del registro. Las sesiones salen de `/run/systemd/sessions` (o
`/run/utmp`); los eventos, de `journalctl -u ssh -u sshd` y de
`/var/log/auth.log*`, sin duplicados.

En un VPS o servidor esto es lo que responde «¿quién ha entrado?». La app
muestra una tarjeta **ACCESOS** en Inicio y una pantalla con sesiones, últimos
accesos, fallos y direcciones. El diagnóstico añade avisos si SSH acepta
contraseñas, permite root con contraseña, acumula fallos o recibe una IP nueva.

El servicio necesita leer los registros: la unit de ejemplo incluye
`SupplementaryGroups=adm systemd-journal`. Sin esos grupos el agente sigue
funcionando y avisa de que los registros no son legibles.

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

La app nativa vive en `app/` (Kotlin + Compose). La interfaz está organizada por
temas: **Resumen**, **Recursos** (CPU, Memoria, Discos, Red, Energía),
**Accesos** (Ahora mismo, Registros de acceso, SSH), **Alertas** y **Más** (Procesos,
Diagnóstico, Permisos, Conexión, Ajustes). El mapa completo y las reglas de
diseño están en [`docs/UI.md`](docs/UI.md). Habla la API real con:

- **Pinning**: `X509TrustManager` propio que compara `sha256(cert.encoded)[..16]`
  con el `pin` del QR. El `HostnameVerifier` acepta el certificado ya validado:
  el pin es la raíz de confianza y un cambio de IP por DHCP no rompe nada.
- **Failover**: recorre los endpoints del QR en cada request y recuerda el que
  funcionó; el topbar muestra `LAN`, `Tailscale` y el estado del transporte.
- **Tiempo real**: SSE con OkHttp; si el stream se cae, polling cada 2,5 s y
  reconexión con backoff.
- **Emparejamiento**: cámara con CameraX + ML Kit, con dos alternativas para
  cuando no hay cámara o permiso: pegar el payload o `-e pair "vitals1:…"`.
- **Vigilancia**: servicio en primer plano con SSE, notificación permanente y
  aviso de host caído en unos 30 s; WorkManager lo revive cada 15 min. Se
  activa en **Alertas › Vigilancia de caída** y el estado se ve en el topbar y
  en Ajustes.
- **Reglas y permisos**: **Alertas** reúne las plantillas (una regla por disco,
  memoria y temperatura), el silencio 1/8/24 h, el riesgo sin regla y el
  historial de 30 días. **Más › Permisos** muestra el riesgo, el estado por
  dispositivo y el código de la solicitud. Requieren token `operator`.
- **Diagnóstico**: hallazgos del host y el comando exacto que los arreglaría,
  en **Más › Diagnóstico**; nunca se ejecutan.
- **Energía**: en un portátil, **Recursos › Energía** muestra batería, estado,
  tiempo restante, consumo, salud, ciclos y una gráfica de 24 h; la pestaña se
  oculta sola en un servidor.
- **Accesos**: **Accesos › Ahora** responde “¿quién está dentro?”;
  **Registros de acceso** lista accesos, fallos y direcciones de 24 h o 7 días; **SSH**
  explica `PasswordAuthentication` y `PermitRootLogin`.

```bash
cd app && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/vitals-app-debug.apk
```

Detalles de arquitectura, firma y limitaciones en [`app/README.md`](app/README.md).

> `CertificatePinner` de OkHttp no sirve aquí: fija hashes SPKI, no el
> certificado completo. Con certificado autofirmado el camino correcto es el
> `TrustManager` del pin.

## Mockup web

`mockup/web` es el prototipo con el que se validó la primera UI antes de
escribir Kotlin. Sigue sirviendo para probar el agente desde el navegador, pero
**no refleja la navegación actual** (4 pestañas frente a los 5 destinos y las
sub-pantallas de la app): la fuente de verdad es [`docs/UI.md`](docs/UI.md).
Publica la web en la LAN y hace de proxy con verificación de pin.

```bash
python3 mockup/serve.py --port 8080
# la cámara del móvil exige HTTPS:
tailscale serve --bg http://127.0.0.1:8080
```

## Verificación

```bash
cargo test                  # red, auth, scopes, command, caps, store
cargo clippy --all-targets
bash scripts/smoke.sh       # end-to-end contra un puerto y estado temporales
cd app && ./gradlew assembleDebug
```

El smoke test cubre: handshake con `--cacert`, 401 sin token, pairing por
socket, intercambio de código, métricas, batería y energía (si el host la
tiene), accesos SSH y reporte de accesos (si son legibles), histórico con
`battery_pct`, procesos, SSE, reutilización de código, revocación, scopes
`reader`/`operator`, esquema cerrado de `command`, límite de 4 KB,
idempotencia, concesión y revocación de capabilities en dos dispositivos
(por lista y por código), auditoría visible sólo para `operator`, reglas de
alerta con histéresis, silencio, borrado y persistencia tras reiniciar, estado
de capabilities por dispositivo y diagnósticos con comandos sugeridos.
La app se probó en un Galaxy A54 contra el agente real.

La convención de commits y el proceso de release están en
[CONTRIBUTING.md](CONTRIBUTING.md).

## Limitaciones conocidas

- IPv4 por defecto. `bind_ipv6 = true` en la configuración añade IPv6.
- Un solo host por sesión. Varios hosts implican varias sesiones, no
  implementado todavía.
- El token va cifrado al Android Keystore con migración automática desde 0.1.x;
  la instalación limpia actual no ejercita esa migración.
- El agente evalúa y guarda alertas; el push por ntfy sigue pendiente.
- La energía del portátil es sólo visualización: no hay alerta de batería baja
  ni detección de suspensión todavía.
- La vigilancia de host caído se validó con pantalla apagada (caída en 32–33 s);
  la medición de batería (<5 % en 8 h) sigue pendiente en dispositivo físico.
- `exec.*` está reservado en 0.2: se autoriza por dispositivo pero no se ejecuta.
- Sin compresión ni paginación: los payloads son pequeños a propósito.
- `IPAddressDeny=any` en la unit impide egreso y también `bind()` a direcciones
  públicas, así que `allow_public = true` exige ampliar esa lista.

## Estado del proyecto

v0.3.3 publicada: contrato `command`, scopes, auditoría, alertas con histéresis
y predicción, watchdog de host caído, token en Keystore, diagnósticos, UI por
temas, **estado del portátil** (batería, AC, consumo, salud, `form_factor` e
histórico de energía) y **accesos SSH** (sesiones, accesos, fallos y
configuración). Servidor, app Android, mockup web y pipeline de release con
changelog generado por conventional commits y release-please.

Release: https://github.com/luismasuarez/vitals/releases/tag/v0.3.3

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
curl -L https://github.com/TU_USUARIO/vitals/releases/download/v0.3.1/vitals-0.3.1-x86_64-unknown-linux-musl.tar.gz | tar xz

# 2. Instala el binario, la unit de systemd y la configuración de ejemplo
sudo install -m 0755 vitals-0.3.1-x86_64-unknown-linux-musl/vitals /usr/local/bin/vitals && sudo install -d /etc/vitals && sudo install -m 0644 vitals-0.3.1-x86_64-unknown-linux-musl/config.toml.example /etc/vitals/config.toml && sudo install -m 0644 vitals-0.3.1-x86_64-unknown-linux-musl/vitals.service /etc/systemd/system/vitals.service

# 3. Arranca el servicio
sudo systemctl daemon-reload && sudo systemctl enable --now vitals
```

El servidor escucha en el puerto **8443** con el certificado autofirmado que
genera en el primer arranque. Después, empareja el móvil con
`sudo vitals pair --name pixel` y sigue el flujo del QR descrito arriba.

### APK de Android

Con el móvil conectado por `adb`:

```bash
adb install -r vitals-0.3.1-android.apk
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
