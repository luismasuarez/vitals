# Prompt para continuar en una sesión limpia

> Copia este archivo completo como primer mensaje de la nueva sesión.

---

## Contexto en una frase

`vitals` es un agente en Rust (binario único, sin runtime) que expone métricas de
un host por HTTPS con certificado propio y pin truncado, más una app Android
nativa en Compose que se empareja escaneando un QR de un solo uso. Sin cuenta,
sin cloud, sin abrir puerto a internet. Repo:
https://github.com/luismasuarez/vitals (público).

## Estado exacto

| Cosa | Valor |
|---|---|
| Rama | `main`, tag `v0.1.4`, HEAD `e278b47` |
| Servidor en este equipo | servicio **de usuario** `systemctl --user vitals`, versión 0.1.4, activo |
| Socket de control | `/run/user/1000/vitals/control.sock` |
| Estado del agente | `~/.local/state/vitals` (SQLite + certificado + clave) |
| App instalada | `dev.vitals.app` 0.1.4, release firmada, `arm64-v8a`, ya emparejada |
| Escucha | `192.168.1.104:8443`, `100.71.72.48:8443`, `127.0.0.1:8443` (IPv4) |
| Firma APK | `~/keystores/vitals-release.jks` + `vitals-release.properties` (0600), secrets ya en GitHub |
| Instalador | `curl -fsSL …/main/scripts/install.sh \| bash` y `vitals-update` |

## Arquitectura (decisiones cerradas, no reabrir sin motivo)

1. **TLS propio con pin truncado** (`sha256(cert DER)[..16]`), no Tailscale Serve.
   El cliente no valida CA ni hostname: el pin es la raíz de confianza.
2. **Bind por dirección explícita**, nunca `0.0.0.0`; sólo LAN/Tailscale/loopback.
   IPv4 únicamente salvo `bind_ipv6 = true`.
3. **QR de un solo uso** (código 40 bits, TTL 5 min) y token por dispositivo; el
   token nunca viaja en el QR.
4. **Socket unix** para administración (CLI ↔ agente), porque `DynamicUser`
   impide que el CLI escriba el estado.
5. **SSE + polling de respaldo** con failover de endpoints; la app adopta la
   lista de endpoints que reporta `/api/v1/info`.
6. **Supervisor de interfaces**: cada 5 s añade/quita listeners y actualiza los
   endpoints (arregló el caso "arrancó sólo con Tailscale").
7. **Instalador idempotente**: detecta si es root, verifica checksums, instala
   servicio de sistema o de usuario, y `vitals-update` reutiliza el mismo script.
8. **APK sólo arm64** (`abiFilters`), firmado con el keystore local; CI firma con
   los mismos secrets, así las actualizaciones instalan encima.

## Estrategia de producto ya decidida (`docs/PRODUCT.md`)

- Apuestas, en orden: **1) alertas sin cuenta**, 2) flota multi-host, 3) compartir
  un host por QR con caducidad.
- El hueco es estrecho y no técnico: se defiende con ejecución móvil y confianza.
- **Un único endpoint mutable**: `POST /api/v1/command` con `{v,id,op,args}`,
  esquema cerrado (`deny_unknown_fields`), ≤4 KB, 60/min, `id` idempotente 24 h.
- **Scopes por dispositivo**: `reader` (GET) y `operator` (GET + command + audit).
- **Permisos por dispositivo**, catálogo cerrado, manifiesto
  `/etc/vitals/capabilities.toml` escrito sólo por root.
- Aprobación por **lista** (`vitals caps pending` → `caps approve 2`) o por
  **código** de 6 caracteres (respaldo).
- **Auditoría**: 90 días o 10 000 filas; `operator` la ve completa.
- **`exec.*` fuera de v0.2 y v0.3**; en v0.4 sólo si ≥30 % de instalaciones
  tienen una regla activa a 30 días. Mientras tanto `env.suggest` propone el
  arreglo sin ejecutarlo.
- Privilegio real, si llega: unidades oneshot preinstaladas o regla polkit con
  allow-list; **el agente nunca corre como root**.

## Próxima entrega: v0.2.0

Alcance cerrado (no ampliar):

1. `POST /api/v1/command` + scopes `reader`/`operator` + manifiesto de permisos.
2. `GET /api/v1/audit` con retención y visibilidad por scope.
3. `op: alerts.set` (disco % y días restantes, memoria, temperatura, silencio)
   con evaluador en el agente, histéresis y deduplicación.
4. Watchdog en la app para "host caído" (servicio en primer plano + notificación
   local; sin egress del agente).
5. Token del dispositivo al Keystore con migración sin re-emparejar.
6. `diagnostics.get` + `env.suggest` (hallazgos estructurados y comando sugerido).
7. Pantalla de reglas (3 plantillas) y pantalla de permisos (concedido/pendiente
   por dispositivo).
8. `scripts/smoke.sh` ampliado y docs al día.

Fuera: multi-host, `exec.*`, logs, compartir, widget, iOS, ntfy (va en v0.2.1
como `write.channels`).

### Criterios de aceptación

Están completos en `docs/PRODUCT.md` (§ MVP de la próxima versión). Los
imprescindibles: `reader` recibe 403 en `command`; un `exec.*` aprobado al
dispositivo A no lo tiene B; `alerts.set` persiste tras reiniciar el agente y
tras `vitals-update`; histéresis ±2 % no duplica alertas; `cargo test`,
`cargo clippy --all-targets`, `scripts/smoke.sh` y `./gradlew assembleDebug` en
verde.

## Orden de trabajo sugerido

1. Esquema de `command` + scopes + `audit` (sin ops de negocio) y sus tests.
2. `alerts.set` + evaluador + histéresis, con `smoke.sh`.
3. Watchdog de la app + notificación local.
4. Keystore y migración del token.
5. `diagnostics.get` + `env.suggest` y pantallas nuevas.
6. Release 0.2.0 con el proceso de siempre.

## Archivos clave

| Ruta | Qué |
|---|---|
| `src/api/` | Router, handlers, SSE |
| `src/auth.rs` | Tokens, códigos de pairing, rate limit |
| `src/control.rs` | Socket unix (pair, devices, info) |
| `src/store.rs` | SQLite, rollups, migraciones |
| `src/net.rs` | Interfaces, `plan_changes`, filtro IPv4 |
| `src/tls.rs` | Certificado y pin |
| `src/main.rs` | CLI, supervisor de listeners, sampler |
| `app/src/main/java/dev/vitals/app/` | ViewModel, cliente con pinning, UI Compose |
| `docs/PRODUCT.md` | Estrategia, contrato, roadmap |
| `docs/TESTING.md` | Instalación, QR, verificación |
| `docs/HANDOFF.md` | Handoff técnico anterior |
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

- `static.rust-lang.org` está bloqueado: Rust se instaló con el mirror de
  Tsinghua (`RUSTUP_DIST_SERVER`).
- `dl.google.com` es inestable desde esta red: `app/settings.gradle.kts` añade
  el mirror de Aliyun **sólo cuando `CI` no está definido**. En CI no se usa.
- Los runners no traen el paquete `tools` de Android: el workflow usa el SDK
  preinstalado, no `android-actions/setup-android`.
- `aarch64` se compila en `ubuntu-24.04-arm` (nativo). Con `cross` + QEMU falla
  por glibc.
- El APK se llama `vitals-app-debug.apk` / `vitals-app-release.apk` (el
  rootProject es `vitals-app`).
- Gradle 8.11.1 vive en `~/.local/share/gradle-8.11.1`; hay wrapper en `app/`.

## Convenciones

- Prosa y documentación en español neutro; código, comentarios, identificadores,
  commits y logs en inglés.
- Commits convencionales. **No commit ni push sin pedido explícito.**
- Documentación y código van juntos: si cambia el contrato, cambia `API.md`,
  `PRODUCT.md` y `smoke.sh` en la misma entrega.
- Cualquier cambio de red o de superficie exige revisar el threat model.

## Preguntas abiertas para la próxima sesión

1. ¿ntfy self-host como canal por defecto o `ntfy.sh` con topic secreto como
   opción fácil? (afecta al egress y al mensaje "sin cuenta").
2. ¿El watchdog de la app con WorkManager (15 min) es suficiente para "host
   caído" o hace falta servicio en primer plano permanente?
3. ¿Retención de alertas y de histórico de alertas? (propuesta: 30 días).
4. ¿La pantalla de permisos muestra el riesgo antes de solicitar? (sí, pero
   definir el texto exacto).
