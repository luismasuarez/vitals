# Prompt para continuar en una sesión limpia

> Copia este archivo completo como primer mensaje de la nueva sesión.

---

## Qué es

`vitals` es un agente en Rust (binario único musl, sin runtime) que expone las
métricas de un host por HTTPS con certificado propio y **pin truncado**, más una
app Android nativa en Compose que se empareja escaneando un **QR de un solo
uso**. Sin cuenta, sin cloud y sin abrir puerto a internet. Repo público:
https://github.com/luismasuarez/vitals

## Estado exacto

| Cosa | Valor |
|---|---|
| Rama / HEAD | `main` en `a2c3c03`, tag `v0.2.0` publicado |
| Servidor local | servicio de usuario `systemctl --user vitals`, 0.2.0, activo, **0 dispositivos** |
| Socket de control | `/run/user/1000/vitals/control.sock` |
| Estado del agente | `~/.local/state/vitals` limpio (SQLite + certificado nuevos, `0700`) |
| App instalada | `dev.vitals.app` 0.2.0, release firmada, datos borrados → onboarding (permiso de notificaciones sin conceder) |
| Pin actual | `0nH9iJ_nwpz7msNw4KcnGQ` |
| Escucha | `192.168.1.104:8443`, `100.71.72.48:8443`, `127.0.0.1:8443` (IPv4) |
| Firma APK | `~/keystores/vitals-release.jks` + `.properties` (0600); secrets en GitHub |
| Instalación | `curl -fsSL …/main/scripts/install.sh \| bash`; actualiza `vitals-update` |

## Decisiones cerradas — no reabrir sin motivo

### Arquitectura

1. **TLS propio con pin truncado** (`sha256(cert DER)[..16]`), no Tailscale
   Serve. El cliente no valida CA ni hostname: el pin es la raíz de confianza.
2. **Bind por dirección explícita**, nunca `0.0.0.0`; sólo LAN, Tailscale y
   loopback. IPv4 salvo `bind_ipv6 = true`.
3. **QR de un solo uso** (código 40 bits, TTL 5 min) y token por dispositivo; el
   token nunca viaja en el QR.
4. **Socket unix** para administración: `DynamicUser` impide que el CLI escriba
   el estado, y así no se abre otro puerto.
5. **SSE + polling de respaldo** con failover; la app adopta la lista de
   endpoints que reporta `/api/v1/info`.
6. **Supervisor de interfaces** cada 5 s: añade y quita listeners y actualiza
   endpoints (resolvió el arranque "sólo con Tailscale").
7. **Instalador idempotente** (sistema o usuario) con checksums; `vitals-update`
   reutiliza el mismo script.
8. **APK sólo arm64**, firmado con el keystore local; CI firma con los mismos
   secrets, así las actualizaciones instalan encima.

### Producto

1. Apuestas, en orden: **1) alertas sin cuenta**, 2) flota multi-host, 3)
   compartir un host por QR con caducidad. El hueco es estrecho y se defiende con
   ejecución móvil y confianza, no con tecnología.
2. **Un único endpoint mutable**: `POST /api/v1/command` con `{v,id,op,args}`,
   esquema cerrado (`deny_unknown_fields`), ≤4 KB, 60/min, `id` idempotente 24 h.
   Nada más cambia de método: todo lo demás sigue siendo GET.
3. **Scopes por dispositivo**: `reader` (GET) y `operator` (GET + command +
   audit). `operator` se obtiene con `vitals pair --operator`.
4. **Permisos por dispositivo**, catálogo cerrado, manifiesto
   `/etc/vitals/capabilities.toml` escrito sólo por root. Aprobación por lista
   (`vitals caps pending` → `caps approve 2`) o por código de 6 caracteres.
5. **Auditoría**: 90 días o 10 000 filas; `operator` la ve completa, `reader`
   recibe 403. Todo intento se audita, incluidos los rechazos.
6. **Historial de alertas**: 30 días, con estado (disparada, entregada,
   silenciada, recuperada).
7. **`exec.*` fuera de v0.2 y v0.3**; en v0.4 sólo si ≥30 % de instalaciones
   tienen una regla activa a 30 días. Mientras tanto `env.suggest` propone el
   comando exacto sin ejecutarlo. El agente nunca corre como root; si algún día
   hay `exec`, será con oneshot preinstaladas o regla polkit con allow-list y
   usuario fijo `vitals`.
8. **Detección en segundos**: watchdog con **servicio en primer plano** + SSE
   (keepalive 15 s, "caído" a los dos perdidos ≈30–45 s). WorkManager queda sólo
   como red de seguridad que revive el servicio cada 15 min.
9. **ntfy self-hosted por defecto**, en loopback (`127.0.0.1`), sin cambiar el
   egress del sandbox. `ntfy.sh` sólo como respaldo documentado.

## Próxima entrega: v0.2.0

Alcance congelado:

1. `POST /api/v1/command` + scopes `reader`/`operator` + manifiesto de permisos.
2. `GET /api/v1/audit` con retención 90 días / 10 000 filas y visibilidad por
   scope.
3. `op: alerts.set` (disco % y días restantes, memoria, temperatura, silencio)
   con evaluador en el agente, histéresis (±2 % no re-alerta) y deduplicación.
4. Watchdog en la app: **servicio en primer plano** con SSE, notificación local,
   estado "vigilando" visible, y job de WorkManager que lo revive.
5. Token al **Keystore** con migración automática sin re-emparejar.
6. `diagnostics.get` + `env.suggest`: hallazgos estructurados y el comando exacto
   que los arreglaría, sin ejecutarlo.
7. Pantalla de reglas (3 plantillas) y pantalla de permisos con estado
   concedido/pendiente **por dispositivo** y el riesgo visible antes de solicitar.
8. `scripts/smoke.sh` ampliado y documentación al día (`API.md`, `PRODUCT.md`,
   `TESTING.md`).

Fuera: multi-host, `exec.*`, logs, compartir, widget, iOS. ntfy entra en v0.2.1
como `write.channels`.

### Criterios de aceptación

Completos en `docs/PRODUCT.md` (§ MVP de la próxima versión). Los que no se
negocian:

- Un token `reader` recibe 403 en `command`; un `operator` sin manifiesto recibe
  `capability_not_granted`.
- Lo aprobado al dispositivo A no lo tiene B; revocar en A no afecta a B;
  aprobar por lista y por código produce la misma concesión.
- `alerts.set` persiste tras reiniciar el agente y tras `vitals-update`.
- Histéresis: oscilar ±2 % alrededor del umbral no genera más de una alerta.
- Host caído: notificación en **<45 s** con el servicio en primer plano y
  pantalla apagada; una sola notificación de recuperación.
- Batería: **<5 % en 8 h** con la vigilancia activa.
- `cargo test`, `cargo clippy --all-targets`, `scripts/smoke.sh` y
  `./gradlew assembleDebug` en verde.

## Fase de pruebas — ejecutada (parcial)

La instalación limpia ya se hizo: agente 0.2.0 con estado nuevo (0 dispositivos,
pin `0nH9iJ_nwpz7msNw4KcnGQ`) y app 0.2.0 con datos borrados en onboarding, sin
permiso de notificaciones para probar también ese flujo. El release `v0.2.0`
está publicado con binarios musl y APK firmado; CI en verde. El binario
instalado salió del propio release (`vitals-update --force`).

Probado en el A54 (Android 16 / API 37):

- Vigilancia con pantalla apagada: `host down after 32–33 s`, notificación
  `"steampunker no responde"`, recuperación única y notificación
  `"steampunker vuelve a responder"`. Servicio `specialUse` en primer plano.
- Reglas desde la UI: plantilla de disco creada en 3 toques, visible como
  `disk-root` disparada; persistió tras reiniciar el agente.
- Permisos: diálogo con `riesgo alto`, solicitud de `exec.unit_restart`, código
  de 6 caracteres, aprobación por código, estado `concedido` y revocación
  visible como `sin conceder`. Un segundo dispositivo no heredó la concesión.
- Diagnóstico en Ajustes: hallazgo real de `/home` al 95.9 % con
  `sudo journalctl --vacuum-size=200M`. El aviso de permisos del state dir
  destapó que la instalación de usuario creaba `0755`; corregido a `0700` en
  código y en la unit antes de publicar.
- Token: Ajustes muestra `Android Keystore` y `Scope: operator`.

Pendiente de probar a mano:

- Escanear el QR de alta desde la pantalla de onboarding (el entorno se dejó sin
  emparejar a propósito). Comando: `vitals pair --operator --name "mi-telefono"`.
- Flujo visual del permiso `POST_NOTIFICATIONS` (en la prueba se concedió por
  adb).
- Revive de WorkManager tras matar el proceso (y comportamiento del fabricante).
- Batería 8 h (<5 %) con Battery Historian.
- Actualización real desde 0.1.4 con `vitals-update`; ya se comprobó
  `--check` (instalado 0.2.0 / disponible 0.2.0).
- Migración de token 0.1.x → 0.2: no aplica en una instalación limpia; queda
  cubierta por código y por la comprobación `run-as` de `TESTING.md`.

## Orden de trabajo

v0.2.0 está cerrado y publicado. Siguiente:

1. **v0.2.1**: `write.channels` + ntfy self-hosted en loopback, estados
   `delivered` en el historial y aviso de entrega.
2. **v0.3**: multi-host, widget, estado del portátil, `vitals setup` para VPS,
   pantalla de permisos completa (solicitud/aprobación desde el móvil).
3. **v0.4**: compartir por QR, `exec.*` real con polkit/oneshot, watcher de
   servicios, logs acotados.

Antes de abrir v0.2.1, cerrar las pruebas manuales pendientes de arriba
(QR desde onboarding, WorkManager, batería y actualización real desde 0.1.4).

## Progreso de v0.2.0

| # | Entrega | Estado |
|---|---|---|
| 1 | `command` + scopes + manifiesto + `audit` | **Hecho**, commit `4e9a394` |
| 2 | `alerts.set` + evaluador + histéresis | **Hecho**, commit `c5d10b3` |
| 3 | Servicio en primer plano + SSE + WorkManager | **Hecho y probado** en el A54 |
| 4 | Token al Keystore | **Hecho**; almacenamiento verificado, migración desde 0.1.x no aplica tras instalación limpia |
| 5 | `diagnostics` + `suggest` + pantallas | **Hecho y probado** en el A54 |
| 6 | Release 0.2.0 | **Publicado** (`v0.2.0`), CI y artefactos correctos |

Implementado en la rebanada 1 (commit `4e9a394`):

- Scope `reader`/`operator` en `devices` y en `pairing_codes`, con migración
  automática de bases 0.1.x. `vitals pair --operator`; el scope viaja en el QR
  sólo como información, la autoridad es la fila del código.
- `POST /api/v1/command` con sobre cerrado, `args` cerrados por operación,
  ≤4 KB auditado, 60/min por dispositivo, `id` idempotente 24 h (sólo éxitos).
- `GET /api/v1/audit` paginado por `id`, retención 90 días / 10 000 filas.
- Catálogo cerrado en `src/caps.rs`, manifiesto TOML `version = 1` con
  `[[grant]]`, escrito por `vitals caps` y leído por el agente en cada comando.
- `caps.request` + `vitals caps pending|approve|list|revoke`, aprobación por
  lista o por código de 6 caracteres, concesión por `device_id`.
- `exec.*` reservado: autoriza (scope + grant + allow-list de unidad/máximo) y
  devuelve `501 not_implemented`; no ejecuta nada.
- Rechazos por scope de `command` también se auditan. `diagnostics` y `suggest`
  quedan como `GET` (decisión de esta sesión), para un `reader` también.

Implementado en la rebanada 2 (commit `c5d10b3`):

- `src/alerts.rs`: validación cerrada de reglas, máquina de estados con
  histéresis (2 puntos / 2 días / 2 °C), predicción por regresión de 7 días y
  evaluador que escribe sólo transiciones.
- `alerts.set` (reemplazo del conjunto, ≤20, `id` estable; una definición
  idéntica conserva estado), `alerts.delete` y `alerts.silence` (1..1440 min,
  resetea disparos y registra `silenced`).
- Tablas `alert_rules`, `alert_states`, `alert_events` (30 días) y
  `disk_usage_1m` (por mount, 30 días, una muestra/minuto).
- `GET /api/v1/alerts`: reglas con estado, eventos paginados y predicciones de
  días restantes.
- El sampler graba uso de disco cada minuto y evalúa cada 5 s; el mantenimiento
  poda alertas y uso de disco.
- Smoke: 3 tipos de regla, validación por `kind`, persistencia tras reiniciar,
  un único `fired` en evaluación repetida, silencio y borrado.

Implementado en la rebanada 3 (commit `ef03e50`, junto con 4–5):

- `watchdog/WatchdogService.kt`: servicio en primer plano (`specialUse`), SSE
  contra el endpoint activo con failover, caída a los 30 s sin eventos y una
  notificación por transición (caída y recuperación). El aviso permanente se
  puede parar desde su propia acción.
- `watchdog/Watchdog.kt`: preferencia persistida, estado observable
  (`Off/Connecting/Watching/Down`) y `PeriodicWorkRequest` cada 15 min.
- `watchdog/WatchdogWorker.kt`: revive el servicio si el sistema lo mata;
  reintenta con backoff si Android 12+ bloquea el arranque en segundo plano.
- UI: interruptor en Ajustes (con permiso de notificaciones), píldora
  `vigilando/caído` en el topbar, banner en Inicio y estado con antigüedad.
- Canales de notificación separados (servicio silencioso, alertas high) e icono
  monocromo; permiso `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_SPECIAL_USE` y
  tipo `specialUse` para evitar el límite de 6 h de `dataSync` en Android 15.
- Logs `VitalsWatchdog` para validar los tiempos con `adb logcat`.
- `assembleDebug` en verde. Pendiente: prueba en el A54 con pantalla apagada,
  medición de batería y confirmación de que el fabricante no bloquea el revive.

Implementado en la rebanada 4 (commit `ef03e50`):

- `core/TokenVault.kt`: AES-256-GCM con clave no exportable en
  `AndroidKeyStore` (alias `vitals.token.v1`), sin autenticación de usuario para
  que el watchdog lea el token con la pantalla apagada. IV aleatorio por
  escritura; en preferencias sólo `token_enc = v1:<iv>:<ct>`.
- `SessionStore`: lee el token cifrado; si encuentra el token en claro de 0.1.x,
  lo cifra, escribe el blob y **después** borra el campo `token` del JSON con
  `commit()`. Fallo de cifrado no rompe la sesión: se conserva el valor antiguo
  y se reintenta al siguiente arranque.
- `save()` devuelve `false` si no pudo cifrar y no persiste nada; la app avisa
  en lugar de volver al texto plano.
- Ajustes muestra `Almacenamiento: Android Keystore`; el token sigue
  enmascarado. Hook de log `VitalsVault`/`VitalsSession` para la migración.
- Validación diferida: instalar 0.2 encima de 0.1.4, comprobar que no pide
  reemparejar y que el XML (build debug) no contiene el token.

Implementado en la rebanada 5 (commit `ef03e50`):

- `src/diagnostics.rs`: hallazgos estructurados de disco, predicción, memoria,
  temperatura, alcanzabilidad, permisos del state dir y ausencia de reglas, con
  el comando exacto de remediación (siempre como texto).
- `GET /api/v1/diagnostics` y `GET /api/v1/suggest` para cualquier token.
- `GET /api/v1/caps`: estado por dispositivo (`granted`/`pending`/
  `not_granted`), riesgo, parámetros y código de la solicitud. El código se
  guarda ahora también en claro en SQLite (la base sigue siendo `0600`) para
  poder mostrarlo al reabrir la pantalla.
- App: `scope` en la sesión y en el QR; pantalla de reglas con 3 plantillas,
  silencio e historial; pantalla de permisos con riesgo visible y diálogo de
  solicitud; diagnóstico con comandos en Ajustes.
- Nuevos modelos/API: alertas, caps, diagnósticos y el sobre `command`.
- Smoke: diagnósticos para `reader`, estado de caps pendiente/concedido/revocado.
- `cargo test` 45, smoke completo y `assembleDebug` en verde. Sin validación en
  dispositivo: reemparejar con `--operator` es parte de la fase de pruebas.

### Decisiones de implementación de 0.2 (ya tomadas)

- Manifiesto: `/etc/vitals/capabilities.toml` en sistema, `<state_dir>/capabilities.toml`
  en usuario, `0644` y escritura atómica. El agente nunca escribe; ausente =
  sin concesiones, corrupto = fail closed.
- `caps.request` no concede: sólo crea la solicitud y el código. Una nueva
  solicitud del mismo dispositivo/capability invalida la anterior.
- `exec.*` en 0.2 es una operación reservada que se autoriza y responde
  `not_implemented`. Así 6b es verificable por API sin adelantar v0.4.
- `limits` de parámetros: unidades con allow-list explícita (sin comodines),
  máximo de bytes ≤ 10^12.
- `alerts.set` reemplaza el conjunto completo (≤20 reglas, `id` estable). Una
  definición idéntica conserva estado e histéresis; una cambiada reinicia.
  `alerts.silence` resetea disparos y deja un evento `silenced` por regla.
- Histéresis = 2 unidades absolutas (puntos porcentuales, días o °C). La
  predicción usa regresión lineal de 7 días, mínimo 10 muestras y 1 hora.

## Archivos clave

| Ruta | Qué |
|---|---|
| `src/api/` | Router, handlers, SSE |
| `src/api/command.rs` | Sobre cerrado, autorización, idempotencia, auditoría |
| `src/alerts.rs` | Reglas, histéresis, predicción, evaluador |
| `src/diagnostics.rs` | Hallazgos y comandos exactos, sin ejecutar |
| `src/caps.rs` | Catálogo, scopes, manifiesto TOML |
| `src/auth.rs` | Tokens, scopes, códigos de pairing/aprobación, rate limit |
| `src/control.rs` | Socket unix (pair, devices, info, caps) |
| `src/store.rs` | SQLite, rollups, migraciones, audit y solicitudes |
| `src/net.rs` | Interfaces, `plan_changes`, filtro IPv4 |
| `src/tls.rs` | Certificado y pin |
| `src/main.rs` | CLI, supervisor de listeners, sampler |
| `app/src/main/java/dev/vitals/app/` | ViewModel, cliente con pinning, UI Compose |
| `app/src/main/java/dev/vitals/app/watchdog/` | Servicio en primer plano, estado y WorkManager |
| `app/src/main/java/dev/vitals/app/ui/Manage.kt` | Pantallas de reglas y permisos |
| `docs/PRODUCT.md` | Estrategia, contrato de `command`, permisos, roadmap |
| `docs/TESTING.md` | Instalación, QR, scopes y aprobación, verificación |
| `docs/NEXT-SESSION.md` | Este archivo |
| `scripts/install.sh` | Instalar/actualizar |
| `scripts/release.sh` | Bump + changelog + tag |
| `.github/workflows/` | CI y release |

## Verificación obligatoria

```bash
cd ~/tools/vitals
export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
cargo test && cargo clippy --all-targets && bash scripts/smoke.sh
cd app && JAVA_HOME=/usr/lib/jvm/jdk-17.0.12-oracle-x64 \
  ~/.local/share/gradle-8.11.1/bin/gradle assembleDebug
```

## Trampas del entorno (ya resueltas, no las redescubras)

- `static.rust-lang.org` bloqueado: Rust se instaló con el mirror de Tsinghua.
- `dl.google.com` inestable: `app/settings.gradle.kts` añade el mirror de Aliyun
  **sólo cuando `CI` no está definido**; en CI no se usa.
- Los runners no traen el paquete `tools`: el workflow usa el SDK preinstalado,
  no `android-actions/setup-android`.
- `aarch64` se compila en `ubuntu-24.04-arm` (nativo); con `cross` + QEMU falla
  por glibc.
- El APK se llama `vitals-app-debug.apk` / `vitals-app-release.apk`.
- Gradle 8.11.1 en `~/.local/share/gradle-8.11.1`; hay wrapper en `app/`.

## Convenciones

- Prosa y documentación en español neutro; código, comentarios, identificadores,
  commits y logs en inglés.
- Commits convencionales. **No commit ni push sin pedido explícito.**
- Si cambia el contrato, cambian `API.md`, `PRODUCT.md` y `smoke.sh` en la misma
  entrega.
- Cualquier cambio de red o de superficie exige revisar el threat model.

## Lo único que queda por precisar

- Texto exacto del aviso de riesgo en la pantalla de permisos (hay una versión
  funcional; falta pulir copy).
- Nombre y copy de la notificación permanente ("vitals vigilando · <host>" es la
  propuesta) y si se oculta cuando no hay reglas activas (propuesta: sí).
