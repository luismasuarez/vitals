# vitals · app Android

Cliente nativo del agente `vitals`. Es la misma interfaz que el mockup web
(`mockup/web`) pero hablando la API real: pinning del certificado, failover de
endpoints y SSE.

## Requisitos

- JDK 17 (`JAVA_HOME` apuntando a él).
- Android SDK con `platforms;android-35` y `build-tools;35.0.0`.
- Gradle 8.11.1 (o el wrapper, si está presente).
- `local.properties` con `sdk.dir=/ruta/al/Android/sdk`.

> Nota del equipo: `dl.google.com` es inestable desde esta red, así que
> `settings.gradle.kts` incluye el mirror de Aliyun como segundo repositorio.
> El SDK también trae un init script que añade el mismo mirror; por eso
> `repositoriesMode` es `PREFER_SETTINGS`.

## Compilar e instalar

```bash
cd app
./gradlew assembleDebug            # o: gradle assembleDebug
adb install -r build/outputs/apk/debug/vitals-app-debug.apk
```

Para una versión concreta (lo usa el workflow de release):

```bash
gradle assembleRelease \
  -PvitalsVersionName=0.3.0 \
  -PvitalsVersionCode=300 \
  -PvitalsKeystore=/ruta/release.jks \
  -PvitalsKeystorePassword=... \
  -PvitalsKeyAlias=... \
  -PvitalsKeyPassword=...
```

Sin `-PvitalsKeystore` el build release queda sin firmar; el pipeline usa
`apksigner` en ese caso.

## Emparejar

1. En el host: `sudo vitals pair --name "Galaxy A54"`.
2. En la app: **Escanear QR**.
3. Si la cámara falla, hay dos alternativas: pegar el payload `vitals1:…` o
   lanzar la app con el intent (útil para pruebas automatizadas):

```bash
adb shell am start -n dev.vitals.app/.MainActivity -e pair "vitals1:..."
```

Para activar o desactivar la vigilancia sin tocar la UI (sólo pruebas):

```bash
adb shell am start -n dev.vitals.app/.MainActivity -e watch 1
adb shell am start -n dev.vitals.app/.MainActivity -e watch 0
```

## Cómo está armada

| Archivo | Responsabilidad |
|---|---|
| `core/Models.kt` | Tipos de la API y parseo con `org.json` (sin dependencias extra) |
| `core/VitalsApi.kt` | `X509TrustManager` con pin truncado, failover y EventSource |
| `core/Session.kt` | Persistencia de la sesión y migración del token |
| `core/TokenVault.kt` | Cifrado del token con Android Keystore (AES-GCM) |
| `AppViewModel.kt` | Estado Compose, SSE, polling de respaldo, pairing |
| `watchdog/WatchdogService.kt` | Servicio en primer plano, SSE, detección y notificaciones |
| `watchdog/Watchdog.kt` | Estado observable, preferencia y job periódico de WorkManager |
| `watchdog/WatchdogWorker.kt` | Revive el servicio cada 15 min si el sistema lo mata |
| `ui/Onboarding.kt` | Cámara con CameraX + ML Kit, pasos de emparejamiento |
| `ui/Screens.kt` | Inicio, CPU, Red, Procesos |
| `ui/Manage.kt` | Reglas de alerta y permisos por dispositivo |
| `ui/Components.kt` | Tarjetas, anillo, gráficos en Canvas, iconos, tab bar |

Decisiones que conviene no deshacer:

- **El pin es la única autoridad de confianza.** No se valida CA ni hostname:
  un cambio de IP por DHCP no rompe nada y un atacante en la LAN no puede
  interponerse sin la clave del host.
- **SSE con `fetch`/EventSource de OkHttp y polling de respaldo** cada 2,5 s si
  el stream cae. El indicador del topbar muestra `SSE` o `polling`.
- **Failover en cada request**: si el endpoint activo no responde, se prueba el
  siguiente del QR y se recuerda el que funcionó.
- **La vigilancia es un servicio en primer plano, no un worker.** WorkManager
  sólo revive el servicio cada 15 min; no detecta caídas. El tipo declarado es
  `specialUse` para evitar el límite de 6 h que Android 15 impone a `dataSync`.

## Vigilancia

Se activa en **Ajustes → Vigilancia**. La primera vez pide permiso de
notificaciones (Android 13+). Mientras está activa:

- Notificación permanente de baja importancia: `vitals vigilando · <host>`.
- El servicio mantiene un SSE y considera caído el host tras 30 s sin ningún
  evento (dos keepalives de 15 s). Avisa una sola vez con una notificación de
  alta importancia.
- Al volver el primer `snapshot`, cancela el aviso de caída y publica uno solo
  de recuperación.
- Si el sistema mata el servicio, WorkManager lo revive cada 15 min (sujeto a
  las restricciones de arranque en segundo plano de Android 12+). Para máxima
  fiabilidad conviene excluir la app de la optimización de batería.
- El estado (`vigilando`, `caído`, `conectando`) también se ve en la píldora del
  topbar y en Inicio.

## Energía

Si el agente reporta `power` (portátiles), **Inicio** muestra una tarjeta con el
porcentaje, estado de carga, tiempo restante o hasta el 100 %, consumo, salud,
ciclos y una gráfica de las últimas 24 h. En servidores y VPS el objeto no
existe y la tarjeta no se dibuja. Los valores salen de `/sys/class/power_supply`
y el agente los normaliza antes de enviarlos; la app no lee sensores locales.

## Reglas y permisos

Ambas pantallas se abren desde **Ajustes** y necesitan un token `operator`:

```bash
vitals pair --operator --name "mi-telefono"
```

- **Reglas**: tres plantillas (disco >85 % o <7 días, memoria >90 %, temperatura
  >85 °C), lista de reglas activas con estado e historial de 30 días. Permite
  silenciar 1/8/24 h y quitar reglas. Crear una regla son tres toques desde
  Inicio.
- **Permisos**: capabilities incluidas en el token y `exec.*` que requieren
  aprobación. Antes de solicitar se muestra el riesgo y se piden los parámetros
  (unidad o tamaño máximo). La solicitud devuelve el código de 6 caracteres que
  se aprueba en el host con `sudo vitals caps approve <código>`.
- **Diagnóstico** (cualquier token): hallazgos y el comando exacto que los
  arreglaría. La app sólo lo muestra; no ejecuta nada.

## Token y Keystore

- La clave es AES-256-GCM, no exportable, alias `vitals.token.v1` en
  `AndroidKeyStore`, sin requisito de autenticación de usuario: el watchdog
  necesita leerla con la pantalla apagada.
- En `SharedPreferences` sólo queda `token_enc = v1:<iv>:<ciphertext>` (base64).
  El pin, los endpoints y el `device_id` siguen en claro porque no son secretos.
- Un token de 0.1.x en claro se cifra en el primer `load()`, se escribe el
  cifrado y después se elimina del JSON. No hay que reemparejar. La migración
  usa `commit()` para no dejar la copia en claro si el proceso muere.
- Ajustes muestra **Almacenamiento: Android Keystore** cuando la migración está
  hecha.
- Si se borra la clave del Keystore, el token cifrado deja de poder descifrarse:
  la app pide reemparejar. `allowBackup=false` evita restaurar preferencias sin
  su clave en otro dispositivo.

## Limitaciones conocidas

- El watchdog duplica la conexión SSE mientras la app está en primer plano: una
  para la UI y otra para el servicio. Es barato, pero conviene unificarlo si se
  añaden más consumidores.
- Android 12+ puede bloquear el arranque del servicio desde WorkManager; abrir
  la app siempre lo relanza. Algunos fabricantes matan el proceso igualmente.
- La medición de batería (<5 % en 8 h) exige un dispositivo físico y Battery
  Historian; no se puede validar en el emulador.
- Sin histórico local: cada rango se pide al host (que sí lo conserva).
- La app asume un host por sesión. Varios hosts implican varias sesiones, no
  implementado todavía.
- La tarjeta de energía no distingue eventos de suspensión/reanudación: sólo
  muestra el estado actual y el histórico de batería y consumo.
