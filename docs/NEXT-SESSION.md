# Prompt para continuar — vitals v0.2.1

> Copia este archivo completo como primer mensaje de la nueva sesión.

---

## Qué es

`vitals` es un agente en Rust (binario único musl, sin runtime) que expone las
métricas de un host por HTTPS con certificado propio y **pin truncado**, más una
app Android nativa en Compose que se empareja escaneando un **QR de un solo
uso**. Sin cuenta, sin cloud y sin abrir puerto a internet. Repo público:
https://github.com/luismasuarez/vitals

Desde v0.2.0 el agente también evalúa reglas de alerta (disco, memoria,
temperatura), guarda un historial de 30 días y la app mantiene una vigilancia de
host caído con un servicio en primer plano. La única escritura remota pasa por
`POST /api/v1/command`, con scopes y permisos por dispositivo.

## Cómo usar este documento

Eres el agente que continúa el proyecto. Antes de tocar código:

1. Lee este archivo completo, `docs/API.md`, `docs/PRODUCT.md` y
   `docs/TESTING.md`.
2. Comprueba el estado real (sección «Primeros pasos»). Si algo no coincide con
   este snapshot, gana el sistema real.
3. No resetees el estado del agente ni borres los datos de la app sin pedido
   explícito: los dispositivos y reglas actuales son pruebas del usuario.
4. No hagas commit ni push sin pedido explícito. Commits convencionales en
   inglés; prosa y documentación en español neutro.
5. Un build, test o typecheck en rojo es bloqueante. Documentación y código van
   juntos.

## Estado exacto

| Cosa | Valor |
|---|---|
| Rama / tag | tag `v0.2.1` en `12155e7`; los commits posteriores son sólo documentación |
| Release | https://github.com/luismasuarez/vitals/releases/tag/v0.2.1 (CI en verde) |
| Artefactos | binarios musl `x86_64`/`aarch64`, `SHA256SUMS` y `vitals-0.2.1-android.apk` |
| Servidor local | `systemctl --user vitals`, 0.2.1, activo, **2 dispositivos** `mi-telefono` (operator) |
| Binario instalado | musl descargado del propio release con `vitals-update --force` |
| Socket de control | `/run/user/1000/vitals/control.sock` |
| Estado del agente | `~/.local/state/vitals` en uso, `0700`; reglas `disk-root` y `memory`; `disk-root` disparada |
| Pin actual | `0nH9iJ_nwpz7msNw4KcnGQ` |
| Escucha | `192.168.1.104:8443`, `100.71.72.48:8443`, `127.0.0.1:8443` (IPv4) |
| App instalada | `dev.vitals.app` 0.2.1 desde el APK publicado (`sha256 0c8d09fd…`), datos conservados |
| App, estado UI | Emparejada como `operator`; notificaciones concedidas; watchdog **vigilando** |
| Dispositivo de pruebas | Galaxy A54, Android 16 (API 37), por adb en `192.168.1.101:35213` |
| Firma APK | `~/keystores/vitals-release.jks` + `.properties` (0600); secrets en GitHub |

Este es un **estado vivo** de las pruebas en curso, no una instalación limpia:
los dos dispositivos y las dos reglas son los que quedaron al validar la app. No
hay concesiones `exec.*` ni manifiesto. Para volver a cero, usa la sección
«Cómo probar desde cero».

> Snapshot al cerrar la sesión (23/11/2026). Si se sigue probando, el número de
> dispositivos, reglas y eventos cambiará; el pin y la versión no.

## Implementado en 0.2.x

### 0.2.1 — corrección de reglas

`androidx.org.json` devuelve el texto `"null"` para un valor JSON `null`, no una
cadena vacía. Las reglas de memoria/temperatura se releían con
`mount = "null"` y el siguiente `alerts.set` fallaba con
`400 schema: mount must be an absolute path`. Se añadió `stringOrNull` para
todos los campos opcionales, se filtra el mount al serializar y se publicó
0.2.1. Es el único cambio funcional de la patch.

### Contrato y permisos

- `POST /api/v1/command` con sobre `{v,id,op,args}`, esquema cerrado
  (`deny_unknown_fields`), ≤4 KB, 60/min por dispositivo e `id` idempotente 24 h
  (sólo se replican éxitos). Todo intento va a `audit`, incluidos los rechazos.
- Scopes `reader` (GET) y `operator` (GET + `command` + `audit` + `alerts` +
  `caps`). Se fijan al emitir el código: `vitals pair` o `vitals pair --operator`.
- Catálogo cerrado de capabilities en `src/caps.rs` y manifiesto TOML
  `version = 1` con filas `[[grant]]` (device, cap, params, approved_at,
  source). Vive en `/etc/vitals/capabilities.toml` (sistema) o
  `<state_dir>/capabilities.toml` (usuario), es `0644` y sólo lo escribe
  `vitals caps`. El agente únicamente lo lee; ausente = sin concesiones,
  corrupto = fail closed.
- Aprobación por lista (`vitals caps pending` → `approve 1`) o por código de 6
  caracteres (`approve 7KQ2M9`). La concesión es por `device_id`; aprobar en A
  no da nada a B y revocar en A no toca a B.
- `GET /api/v1/audit`: 90 días o 10 000 filas; `operator` la ve, `reader` 403.
- `exec.*` está **reservado**: con concesión, `command` responde
  `501 not_implemented` sin ejecutar nada. Sirve para verificar el modelo de
  permisos sin adelantar v0.4.

### Alertas

- `alerts.set` reemplaza el conjunto (≤20 reglas, `id` estable). Una definición
  idéntica conserva estado e histéresis; una cambiada empieza de cero.
- `alerts.delete` y `alerts.silence` (1..1440 min). Silenciar resetea disparos y
  deja un evento `silenced` por regla que estaba disparada.
- Evaluador en el agente cada 5 s con histéresis de 2 unidades (puntos
  porcentuales, días o °C). Sólo escribe transiciones `fired`/`recovered`.
- Predicción de disco: una muestra por mount y minuto (`disk_usage_1m`, 30
  días), regresión lineal de 7 días, mínimo 10 muestras y 1 hora. Una serie
  plana o que decrece no predice días.
- `GET /api/v1/alerts`: reglas con estado, eventos paginados y predicciones.
  Retención de historial: 30 días.

### Watchdog de la app

- `WatchdogService` en primer plano (`specialUse`, no `dataSync`, para evitar el
  límite de 6 h de Android 15). Mantiene un SSE, rota endpoints y considera
  caído el host tras **30 s sin eventos** (dos keepalives de 15 s).
- Una notificación por caída y una por recuperación; canal de estado silencioso
  y canal de alertas de importancia alta. Acción para dejar de vigilar.
- `WatchdogWorker` (WorkManager, 15 min) revive el servicio si el sistema lo
  mata; si Android 12+ bloquea el arranque en segundo plano, reintenta.
- UI: interruptor en Ajustes, píldora `vigilando/caído` en el topbar, banner en
  Inicio y estado con antigüedad del último dato.

### Token, diagnósticos y pantallas

- Token cifrado con AES-256-GCM en Android Keystore (alias `vitals.token.v1`),
  sin autenticación de usuario para que el watchdog lo lea con pantalla apagada.
  En preferencias sólo queda `token_enc = v1:<iv>:<ct>`. Migración automática
  desde el token en claro de 0.1.x, sin reemparejar.
- `GET /api/v1/diagnostics` y `GET /api/v1/suggest` (cualquier token): hallazgos
  de disco, predicción, memoria, temperatura, alcanzabilidad, permisos del state
  dir y reglas ausentes. El comando propuesto es texto; **nadie lo ejecuta**.
- `GET /api/v1/caps` (operator): estado `granted`/`pending`/`not_granted` por
  dispositivo, riesgo, parámetros y código pendiente.
- App: scope en la sesión/QR; pantalla de reglas con 3 plantillas, silencio e
  historial; pantalla de permisos con riesgo visible y diálogo de solicitud;
  diagnóstico y comandos en Ajustes.

## Modelo de permisos, en concreto

| Capability | Qué permite en 0.2.x | Cómo se obtiene |
|---|---|---|
| `read.metrics` | `GET /metrics` (última muestra) | con el scope |
| `read.processes` | `GET /processes` | con el scope |
| `read.history` | `GET /history` | con el scope |
| `read.audit` | `GET /audit` | sólo `operator` |
| `write.alerts` | `alerts.set/delete/silence` y `GET /alerts` | sólo `operator` |
| `write.channels` | reservado para ntfy en 0.2.2 | no disponible todavía |
| `exec.unit_restart` | autoriza y responde `not_implemented`; allow-list de unidades | aprobación en el host |
| `exec.journal_vacuum` | ídem, con `max_bytes` | aprobación en el host |
| `exec.pkg_cache_clean` | ídem, con `max_bytes` | aprobación en el host |

Matices que conviene no olvidar:

- Los `read.*` y `write.alerts` vienen con el scope; `vitals caps revoke` sólo
  elimina concesiones del manifiesto (`exec.*`). Para bajar de `operator` a
  `reader` hay que revocar el dispositivo y emparejar de nuevo sin `--operator`.
- En la práctica cualquier token válido puede usar `GET /host`, `/info`,
  `/stream`, `/diagnostics` y `/suggest`. El scope se aplica por ruta;
  `command`, `audit`, `alerts` y `caps` son las de `operator`.
- `exec.*` no ejecuta nada en 0.2.x. Una concesión exec sólo habilita la
  autorización y el estado en la pantalla de permisos.
- `write.alerts` no envía notificaciones: sólo gestiona reglas y silencio.
- El código de aprobación se guarda en claro en SQLite para poder mostrarlo al
  reabrir la pantalla; la base es `0600`, el código caduca en 10 min y sólo lo
  valida el host.

## Decisiones cerradas — no reabrir sin motivo

### Arquitectura

1. **TLS propio con pin truncado** (`sha256(cert DER)[..16]`), nunca Tailscale
   Serve. El cliente no valida CA ni hostname: el pin es la raíz de confianza.
2. **Bind por dirección explícita**, nunca `0.0.0.0`; sólo LAN, Tailscale y
   loopback. IPv4 salvo `bind_ipv6 = true`.
3. **QR de un solo uso** (código 40 bits, TTL 5 min) y token por dispositivo.
   El token nunca viaja en el QR; el scope del QR es informativo, la autoridad
   es la fila del código en el agente.
4. **Socket unix** para administración: `DynamicUser` impide que el CLI escriba
   el estado y no se abre otro puerto.
5. **SSE + polling de respaldo** con failover; la app adopta los endpoints que
   reporta `/api/v1/info`.
6. **Supervisor de interfaces** cada 5 s: añade/quita listeners y actualiza
   endpoints.
7. **Instalador idempotente** (sistema o usuario) con checksums; `vitals-update`
   reutiliza el mismo script.
8. **APK sólo arm64**, firmado con el keystore local; CI firma con los mismos
   secrets, así las actualizaciones instalan encima.
9. **State dir `0700`** en sistema y usuario; contiene base de datos, clave TLS
   y hashes de tokens.

### Producto

1. Apuestas, en orden: **alertas sin cuenta**, flota multi-host y compartir un
   host por QR con caducidad. El hueco se defiende con móvil y confianza, no con
   tecnología.
2. **Un único endpoint mutable**, `POST /api/v1/command`; todo lo demás es GET.
3. **Scopes** `reader`/`operator`; permisos de alto riesgo **por dispositivo**.
4. **Manifiesto escrito sólo por root** (o el dueño del servicio en instalación
   de usuario); la API jamás lo modifica.
5. **Auditoría** de 90 días / 10 000 filas; rechazos incluidos.
6. **Historial de alertas** de 30 días con `fired`/`recovered`/`silenced`
   (0.2.x). `delivered` llega con `write.channels` en 0.2.2.
7. **`exec.*` fuera de 0.2 y 0.3**; en 0.4 sólo con oneshot preinstaladas o
   polkit con allow-list y usuario fijo. El agente nunca corre como root.
8. **Detección en segundos**: servicio en primer plano + SSE; WorkManager sólo
   como red de seguridad cada 15 min.
9. **ntfy self-hosted en loopback** por defecto en 0.2.2; `ntfy.sh` sólo como
   respaldo documentado.
10. **Diagnósticos como GET** para que un `reader` vea qué le falta sin pedir
    permisos. Los comandos son sugerencias, nunca ejecución.

## Pruebas realizadas

En local (última ejecución antes del release):

| Check | Resultado |
|---|---|
| `cargo test` | 46/46 |
| `cargo clippy --all-targets` | limpio |
| `cargo fmt --check` | limpio |
| `bash scripts/smoke.sh` | completo |
| `gradle assembleDebug` | verde |
| CI release `v0.2.1` | verde; artefactos y SHA publicados |

En el A54 (Android 16 / API 37), sobre la instalación limpia y la actualización
0.2.0 → 0.2.1:

- Vigilancia con pantalla apagada: caída detectada a los **32–33 s**,
  notificación `"steampunker no responde"`, recuperación única y notificación
  `"steampunker vuelve a responder"`. Servicio `specialUse` confirmado en
  `dumpsys`.
- Reglas: plantilla de disco creada desde la UI en 3 toques, visible como
  `disk-root` disparada; persistió tras reiniciar el agente. Memoria se añadió
  como segunda regla. El smoke cubre además temperatura, histéresis, silencio y
  borrado.
- Permisos: diálogo con `riesgo alto`, solicitud de `exec.unit_restart` con
  unidad, código `8S7YPE`, aprobación por código, estado `concedido` y
  revocación `sin conceder`. Un segundo token no heredó la concesión.
- Diagnóstico: hallazgo real `/home` al 95.9 % con
  `sudo journalctl --vacuum-size=200M`. El mismo diagnóstico destapó que la
  instalación de usuario creaba el state dir `0755`; se corrigió a `0700` antes
  de publicar 0.2.0.
- Token: Ajustes mostró `Almacenamiento: Android Keystore` y `Scope: operator`.
- Alta desde la app y permiso de notificaciones: hechos en el dispositivo (dos
  dispositivos `mi-telefono`, `POST_NOTIFICATIONS` concedido).
- Actualización real 0.2.0 → 0.2.1 con `vitals-update`: binario musl del release
  con checksums, servicio reiniciado y **dispositivos y reglas conservados**.
  APK 0.2.1 publicado (`sha256 0c8d09fd…`) instalado encima sin perder datos.
- Se detectó el bug de `mount = "null"` leyendo la auditoría
  (`alerts.set` rechazado con `schema`). Corregido en 0.2.1.

## Pendiente de probar

- Reintentar la plantilla de **temperatura** en la app con 0.2.1: el bug de
  `mount = "null"` está corregido, pero no se pudo verificar en el dispositivo
  porque estaba en uso. Memoria ya quedó añadida; temperatura debería aceptarse
  sin `schema error`.
- Revive de WorkManager al matar el proceso y comportamiento del fabricante.
- Batería **<5 % en 8 h** con pantalla apagada (Battery Historian).
- Actualización real desde **0.1.4** con `vitals-update`. La 0.2.0 → 0.2.1 sí se
  probó y conservó datos; falta el salto desde 0.1.4 y la migración de token.
- Migración de token 0.1.x → 0.2: no aplica a la instalación limpia; el camino
  está cubierto por código y por la comprobación `run-as` de `TESTING.md`.
- Silencio desde la UI (la API y el smoke lo cubren).
- Historial `delivered` y ntfy: 0.2.2.

## Primeros pasos sugeridos

1. Comprobar el estado real:

   ```bash
   systemctl --user is-active vitals
   vitals --version
   vitals devices list
   vitals tls show
   adb devices
   ```

2. Preguntar al usuario si prefiere cerrar las pruebas pendientes o pasar a
   0.2.2. No resetear nada por iniciativa propia.
3. Si toca cerrar pruebas: reintentar la plantilla de temperatura en 0.2.1,
   revisar la auditoría y anotar el resultado.
4. Si todo está verde: actualizar este archivo y solo entonces planificar 0.2.2
   (ntfy, `write.channels`, `delivered`).
5. Si se toca código: `cargo test`, `clippy`, `fmt`, `smoke.sh` y
   `assembleDebug` antes de release; después `scripts/release.sh`, push de
   `main` y del tag.

## Cómo probar desde cero

```bash
export PATH="$HOME/.local/bin:$PATH"

# 1. Emparejar como operator (reglas/permisos)
vitals pair --operator --name "mi-telefono"
# Escanear el QR. Para sólo métricas: vitals pair --name "mi-telefono"

# 2. Conceder notificaciones en la app (Ajustes → Vigilancia)

# 3. Vigilancia: activar, apagar pantalla, parar el agente y volver a arrancarlo
systemctl --user stop vitals     # caída esperada en <45 s
systemctl --user start vitals    # una sola recuperación

# 4. Reglas: Ajustes → Reglas de alerta → Añadir (disco/memoria/temperatura)
# 5. Permisos: Ajustes → Permisos → Solicitar exec.unit_restart
vitals caps pending
vitals caps approve <código>
vitals caps list
vitals caps revoke <device_id> exec.unit_restart

# 6. Diagnóstico: Ajustes → Diagnóstico (texto y comandos, sin ejecutar)
```

Logs y comprobaciones útiles:

```bash
adb logcat -s VitalsWatchdog
adb shell dumpsys activity services dev.vitals.app | grep -E "isForeground|Watchdog"
adb shell dumpsys notification --noredact | grep -iE "no responde|vuelve a responder"
vitals tls show
vitals devices list
```

Para repetir una prueba totalmente limpia (destructivo: borra tokens, reglas e
historial del host y los datos de la app):

```bash
systemctl --user stop vitals
rm -rf ~/.local/state/vitals
systemctl --user start vitals
adb shell pm clear dev.vitals.app
```

`pm clear` también revoca el permiso de notificaciones, que es lo que interesa
probar desde la UI.

## Archivos clave

| Ruta | Qué |
|---|---|
| `src/api/command.rs` | Sobre cerrado, autorización, idempotencia, auditoría |
| `src/api/handlers.rs` | Router handlers, SSE, audit, alerts, caps, diagnostics |
| `src/alerts.rs` | Reglas, histéresis, predicción, evaluador |
| `src/caps.rs` | Catálogo, scopes, manifiesto TOML |
| `src/diagnostics.rs` | Hallazgos y comandos exactos, sin ejecutar |
| `src/auth.rs` | Tokens, scopes, pairing/aprobación, rate limit |
| `src/control.rs` | Socket unix (pair, devices, info, caps) |
| `src/store.rs` | SQLite, migraciones, audit, alertas, disk_usage |
| `src/config.rs` | Config, rutas, state dir `0700` |
| `src/net.rs` | Interfaces, listeners, filtro IPv4 |
| `src/tls.rs` | Certificado y pin |
| `src/main.rs` | CLI, supervisor, sampler y evaluador |
| `app/.../core/TokenVault.kt` | Token AES-GCM en Android Keystore |
| `app/.../core/Session.kt` | Sesión, scope y migración del token |
| `app/.../watchdog/` | Servicio en primer plano, estado y WorkManager |
| `app/.../ui/Manage.kt` | Pantallas de reglas y permisos |
| `app/.../ui/Sheets.kt` | Ajustes, vigilancia y diagnóstico |
| `docs/API.md` | Contrato completo de la API |
| `docs/PRODUCT.md` | Estrategia, permisos y roadmap |
| `docs/TESTING.md` | Instalación, QR, watchdog y pruebas manuales |
| `scripts/install.sh` | Instalar/actualizar (sistema o usuario) |
| `scripts/release.sh` | Bump + changelog + tag (no hace push) |
| `.github/workflows/` | CI y release |

## Verificación obligatoria

```bash
cd ~/tools/vitals
export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
cargo test && cargo clippy --all-targets && cargo fmt --check
bash scripts/smoke.sh
cd app && JAVA_HOME=/usr/lib/jvm/jdk-17.0.12-oracle-x64 \
  ~/.local/share/gradle-8.11.1/bin/gradle assembleDebug
```

Para un APK release firmado en local:

```bash
cd app
set -a; source ~/keystores/vitals-release.properties; set +a
JAVA_HOME=/usr/lib/jvm/jdk-17.0.12-oracle-x64 \
  ~/.local/share/gradle-8.11.1/bin/gradle assembleRelease \
  -PvitalsVersionName=0.2.2 -PvitalsVersionCode=202 \
  -PvitalsKeystore="$storeFile" -PvitalsKeystorePassword="$storePassword" \
  -PvitalsKeyAlias="$keyAlias" -PvitalsKeyPassword="$keyPassword"
```

## Proceso de release

```bash
scripts/release.sh 0.2.2      # bump Cargo/app/CHANGELOG y tag local
git push origin main
git push origin v0.2.2        # CI publica binarios + APK
vitals-update --check         # o --force para reinstalar el release
```

`release.sh` **no hace push**. El tag dispara `.github/workflows/release.yml`.
El binario local puede instalarse desde el release con `vitals-update --force`,
que verifica checksums.

## Trampas del entorno (ya resueltas)

- `static.rust-lang.org` bloqueado: Rust se instaló con el mirror de Tsinghua.
- `dl.google.com` inestable: `app/settings.gradle.kts` añade el mirror de Aliyun
  sólo cuando `CI` no está definido; en CI sólo repos oficiales.
- Los runners no traen el paquete `tools`: el workflow usa el SDK preinstalado.
- `aarch64` se compila en `ubuntu-24.04-arm` nativo; `cross` + QEMU falla.
- Gradle 8.11.1 en `~/.local/share/gradle-8.11.1`; hay wrapper en `app/`.
- El A54 está por adb en `192.168.1.101:35213` (puede cambiar de IP/puerto).
- `adb uninstall` puede fallar con `DELETE_FAILED_INTERNAL_ERROR`; `pm clear` +
  `adb install -r` deja el mismo resultado limpio.
- `pm clear` borra datos y permisos runtime, incluido `POST_NOTIFICATIONS`.
- Hooks de prueba: `adb shell am start -n dev.vitals.app/.MainActivity -e pair
  "vitals1:…"` y `-e watch 1|0`.
- `vitals devices` sin subcomando imprime ayuda; usar `vitals devices list`.
- El APK se llama `vitals-app-debug.apk` / `vitals-app-release.apk`; el asset
  del release es `vitals-0.2.1-android.apk`.

## Convenciones

- Prosa y documentación en español neutro; código, comentarios, identificadores,
  commits y logs en inglés.
- Commits convencionales. **No commit ni push sin pedido explícito.**
- Si cambia el contrato, cambian `API.md`, `PRODUCT.md`, `TESTING.md` y
  `smoke.sh` en la misma entrega.
- Cualquier cambio de red o de superficie exige revisar el threat model.
- Un build/typecheck en rojo es bloqueante. Documentación y código van juntos.

## Criterios de aceptación de v0.2

- Un token `reader` recibe 403 en `command`; un `operator` sin manifiesto recibe
  `capability_not_granted`.
- Aprobado a A no lo tiene B; revocar A no afecta a B; aprobar por lista y por
  código produce la misma concesión.
- `alerts.set` persiste tras reiniciar el agente y tras `vitals-update`.
- Histéresis: oscilar ±2 % alrededor del umbral no genera más de una alerta.
- Host caído: notificación en <45 s con el servicio en primer plano y pantalla
  apagada, y una sola notificación de recuperación. **Validado: 32–33 s.**
- Batería <5 % en 8 h con la vigilancia activa. **Pendiente.**
- `cargo test`, `cargo clippy --all-targets`, `smoke.sh` y `assembleDebug` en
  verde. **Verde** (46 tests).

## Próximos pasos

1. **v0.2.2**: `write.channels` + ntfy self-hosted en loopback, estado
   `delivered` en el historial y aviso de entrega. Añadir `read.alerts` al
   catálogo o documentar definitivamente que `GET /alerts` va con `operator`.
2. **v0.3**: multi-host, widget, estado del portátil, `vitals setup` para VPS y
   pantalla de permisos completa con aprobación desde el móvil.
3. **v0.4**: compartir por QR con caducidad, `exec.*` real con polkit/oneshot,
   watcher de servicios y logs acotados.

Antes de abrir 0.2.2, cerrar las pruebas manuales pendientes (temperatura en
0.2.1, WorkManager, batería y update real desde 0.1.4).

## Lo único que queda por precisar

- Copy definitivo de la notificación permanente y si debe ocultarse cuando no
  hay reglas activas (hoy el watchdog es un interruptor manual; el producto
  proponía activarlo sólo con reglas).
- Copy del aviso de riesgo en la pantalla de permisos (hay una versión
  funcional).
- Si `read.alerts` entra en el catálogo o si `GET /alerts` se queda como ruta
  de `operator` sin capability propia.
- Estrategia para el revive de WorkManager en fabricantes agresivos: exclusión
  de batería guiada, receiver de boot, o ambos.
- Si el watchdog debe activarse automáticamente cuando hay reglas o quedarse
  como interruptor manual del usuario.
