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
| Rama / HEAD | `main` en `b2d9a35`, tag `v0.1.4` |
| Servidor local | servicio de usuario `systemctl --user vitals`, 0.1.4, activo |
| Socket de control | `/run/user/1000/vitals/control.sock` |
| Estado del agente | `~/.local/state/vitals` (SQLite + certificado + clave) |
| App instalada | `dev.vitals.app` 0.1.4, release firmada, `arm64-v8a`, ya emparejada |
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

## Orden de trabajo sugerido

1. Esquema de `command` + scopes + `audit` (sin ops de negocio) con tests.
2. `alerts.set` + evaluador + histéresis, con `smoke.sh`.
3. Servicio en primer plano + SSE + notificación + revive de WorkManager.
4. Keystore y migración del token.
5. `diagnostics.get` + `env.suggest` y pantallas nuevas.
6. Release 0.2.0 con el proceso habitual (`scripts/release.sh 0.2.0`).

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
| `docs/PRODUCT.md` | Estrategia, contrato de `command`, permisos, roadmap |
| `docs/TESTING.md` | Instalación, QR, verificación |
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

- Texto exacto del aviso de riesgo en la pantalla de permisos.
- Nombre y copy de la notificación permanente ("vitals vigilando · <host>" es la
  propuesta) y si se oculta cuando no hay reglas activas (propuesta: sí).
