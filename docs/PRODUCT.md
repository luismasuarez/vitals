# Estrategia de producto — vitals

> Documento de estrategia, no de implementación. Precios y datos de mercado verificados en noviembre de 2026; lo que no se pudo contrastar va marcado `(sin verificar)`. Las personas se derivan de hilos públicos y de la propia arquitectura, no de entrevistas: son hipótesis hasta validarlas con 5 llamadas reales.

## Resumen ejecutivo

1. Hoy vitals es un **visor excelente de un host** y un **monitor incompleto**: sin alertas, el usuario tiene que acordarse de abrir la app. Eso decide si el producto retiene o se desinstala.
2. Ningún competidor reúne las seis piezas que ya tenemos juntas: app nativa, sin cuenta, sin exponer puerto, un binario, emparejamiento por QR y sólo lectura. El más cercano es Beszel, que no tiene app oficial [E2].
3. El hueco es **estrecho y no técnico**: se defiende con ejecución móvil y confianza, no con features que se copian en un sprint.
4. Tres apuestas: **alertas sin cuenta**, **todos los hosts en una pantalla** y **compartir un host por QR con caducidad**.
5. El MVP (v0.2) cubre sólo la primera: capacidad y host caído, con el agente evaluando umbrales y el móvil vigilando la conexión.
6. Métrica que decide: 40 % de instalaciones con una regla activa a los 7 días.
7. Riesgo mayor: sin cuenta no hay SaaS. El proyecto se sostiene como OSS de coste marginal cero o busca una vía de pago que no traicione el modelo.

## Personas y JTBD

| Persona | Trabajo que intenta resolver (textual) | Qué usa hoy | Dónde le duele |
|---|---|---|---|
| **Homelab / self-hosting** (3–8 máquinas, Jellyfin, Proxmox, NAS) | «Quiero enterarme de que el disco del NAS se está llenando sin abrir el portátil ni publicar el panel en internet.» | Beszel o Netdata + Telegram; muchos, nada: «mis agentes 24/7 son mi mujer y mis hijos» [E27] | Montar hub + agente + bot; la app de Beszel es de terceros [E2]; Netdata «es difícil de navegar en el móvil» [E26] |
| **Dev con VPS** (1–3 VPS de 2–4 GB) | «Necesito saber si el VPS se quedó sin disco o sin RAM antes de que el cliente lo note.» | `ssh` + htop/btop, Termius, gráficas del proveedor, Uptime Kuma | El móvil no es un terminal; Grafana no cabe en 2 GB [E28]; Kuma no mide el host [E12] |
| **Portátil + Tailscale** (dev remoto, workstation) | «Quiero ver desde el móvil si la máquina que dejé compilando sigue viva, caliente o sin batería.» | `tailscale status` (sólo conectividad) [E24], SSH + btop | Nadie muestra batería, térmica y suspensión en el móvil sin pasar por la nube |
| **Familia / pequeña oficina** (NAS, PC de oficina, router) | «Que me avise si el servidor de la oficina se cayó, sin contratar nada ni montar un stack.» | Nada, o un sobrino con Uptime Kuma; WhatsApp | Nadie mira un dashboard: «los monitores sin notificaciones son un dashboard que olvidas abrir» [E29] |

**Persona transversal: el "familiar técnico".** Administra 2–4 máquinas ajenas (padres, socio, cliente pequeño) y hoy usa Termius o AnyDesk. Es quien instala, empareja y luego comparte. No es un mercado aparte: es el canal de distribución de las otras cuatro.

**Validación pendiente (no hacer roadmap sin esto):** 5 llamadas de 20 min — 2 homelab, 1 VPS, 1 portátil, 1 oficina. Preguntar por el último incidente real y qué hicieron a las 23:00, no por features deseadas.

## Panorama competitivo

| Herramienta | Hace bien | Dónde duele | Puesta en marcha | Cuenta / cloud | Móvil |
|---|---|---|---|---|---|
| **Beszel** | Hub + agente, histórico, Docker, S.M.A.R.T., alertas vía Shoutrrr (ntfy, Telegram…) [E1][E3] | Dos piezas [E4]; hub que mantener y alcanzar; app **no oficial** y clientes que exigen instancia accesible [E2] | ~30 min; VPS de $5 si no hay dónde [E30] | No (cuenta local del hub) | Terceros, no oficial [E2] |
| **Netdata** (+ Cloud) | Agente zero-config, cientos de alertas, retención local, app nativa con push [E5][E6] | La app y el multi-nodo empujan a Cloud; UI densa en móvil [E26]; app con 2,1★ [E7] | 1 línea | Sí para app y multi-nodo (gratis ≤5 nodos) [E5][E6] | Oficial, mala valoración [E7] |
| **Glances** | TUI, web y API completas; contenedores, sensores, batería [E8] | Sin auth por defecto, sin histórico persistente propio, requiere Python [E8] | `pip` + unidad systemd | No | Web responsive, sin app [E8] |
| **Prometheus + Grafana + node_exporter** | Estándar, consultable, extensible [E9] | 4 servicios, GB de RAM, PromQL, el móvil OSS no existe [E11][E28] | Horas | No en self-host; Cloud para la app [E10][E11] | App sólo con Grafana Cloud [E11] |
| **Uptime Kuma** | Checks HTTP/TCP/ping y 90+ notificaciones [E12] | No mide CPU/RAM/disco; sin app oficial (sólo terceros) [E13]; el socket de Docker da control total [E14] | 10 min | No | Terceros, algunos de pago [E13][E32] |
| **Home Assistant** | Automatizaciones, notificaciones locales, sensores del móvil [E15][E16] | Es un hub domótico, no un monitor; YAML; push por FCM por defecto [E15] | Horas | No (Cloud opcional) | Oficial y buena [E15] |
| **Cockpit** | systemd, logs, storage, red, terminal [E17] | Sin histórico; UI de escritorio; un host por pestaña [E17] | `apt install` | No | Web, pobre [E17] |
| **Portainer** | Docker multi-entorno, stacks, RBAC [E17] | Sólo Docker; sin métricas de host ni histórico; sin app [E18] | 10 min | No (BE de pago) | Navegador [E18] |
| **Dozzle** | Logs en vivo, 7 MB, multi-host, búsqueda SQL [E19] | Sólo logs; sin histórico ni capacidad [E19] | 1 contenedor | No | Navegador [E19] |
| **btop / htop** | Instantánea perfecta en terminal | Local, sin histórico, sin avisos | 0 | No | No |
| **Proxmox VE Companion** | Nativo, VMs/LXC, RRD, consola [E20] | Sólo Proxmox; no hosts genéricos; 3,9★ [E21] | Tener PVE | No | Oficial [E20] |
| **Termius** | SSH multiplataforma sólido | No monitoriza nada; sync de pago [E22] | 5 min | Cuenta para sincronizar [E22] | Oficial |
| **Tailscale** | Conectividad, ACL, métricas del tailnet vía Prometheus [E23][E24] | No hay CPU/RAM/disco, ni histórico, ni alertas; el `/metrics` es de red, no del host [E24] | 10 min | Sí (identidad) [E23] | App de Tailscale, sin dashboards [E24] |

**Lectura del cuadro.** El mercado está partido en dos: herramientas de servidor con web de escritorio y móvil malo (Beszel, Netdata, Grafana, Kuma) y herramientas de móvil que no miden el host (Termius, Tailscale, Proxmox). El precio no es la barrera: casi todo es gratis o ≤$10/mes [E10][E22][E23]; la barrera es la fricción y el móvil.

## El hueco

| Pieza | vitals | Beszel | Netdata | Uptime Kuma | Proxmox / Termius |
|---|---|---|---|---|---|
| App Android nativa | ✅ | ❌ (terceros) | ✅ | ❌ | ✅ / ✅ |
| Sin cuenta ni cloud | ✅ | ✅ | ❌ | ✅ | ✅ / ⚠️ |
| Sin exponer puerto a internet | ✅ | ⚠️ (hub alcanzable) | ✅ agente / ❌ cloud | ⚠️ | ⚠️ |
| Un solo binario sin runtime | ✅ | ⚠️ (hub + agente) | ✅ | ❌ (Node) | ❌ |
| Emparejamiento por QR en 60 s | ✅ | ❌ | ❌ | ❌ | ❌ |
| Alertas | ❌ | ✅ | ✅ | ✅ | ❌ |
| Multi-host | ❌ | ✅ | ✅ | ✅ (checks) | ✅ |

**Veredicto crítico: el hueco es real pero estrecho.** Ninguna herramienta de la tabla tiene las siete casillas, y vitals tiene cinco: le faltan justo las dos que más pesan, alertas y multi-host. Eso es una ventaja de producto, no un foso. Se sostiene sólo si se cumplen tres condiciones: (a) el móvil es *el* producto y no un añadido; (b) existen alertas, porque sin ellas no hay retención [E29]; (c) instalar y emparejar sigue costando menos de dos minutos. Si Beszel publica app oficial o Netdata arregla la suya [E7], la diferencia se reduce a "sin cuenta", que es un nicho de decenas de miles de personas, no de millones. El pinning y el QR son imitables en un sprint; la confianza y la disciplina de superficie mínima, no.

## Candidatos de valor

Esfuerzo: S ≤1 semana · M 2–4 semanas · L 1–2 meses (un dev, este repo).

| # | Candidato | Problema que resuelve | Encaje con la arquitectura | Esf. | Defendibilidad | Riesgo |
|---|---|---|---|---|---|---|
| 1 | **Alertas al móvil sin cuenta** (ntfy/UP/webhook + umbrales) | «Que me avise» es el trabajo real; hoy hay que mirar | El histórico ya calcula el futuro; ntfy self-host en LAN entra en la allow-list de egress actual [E25] | M | Alta si el mensaje es "sin cuenta ni cloud" | Necesita egress opt-in; OEM que mata el proceso |
| 2 | **Watcher de servicios y contenedores** (TCP/HTTP local, unit state) | ¿Está vivo Postgres? ¿reinició Jellyfin? | Probes desde el agente, sin socket de Docker (que da control total [E14]) | M | Media | El sandbox no ve systemd/D-Bus; hay que leer cgroups o pedir un helper |
| 3 | **Compartir host por QR temporal** | Ayudar a un familiar sin darle tu token ni tu tailnet | Reusa QR, TTL y tokens por dispositivo; alcance de sólo lectura y revocación | M | **Alta**: nadie ofrece acceso scoped, caducable y sin cuenta | Complejidad de alcance; malentendidos de "esto es mío" |
| 4 | **Multi-host y vista global** | Un homelab no es un host; hoy el techo es 1 | Fan-out en el cliente: N sesiones, N pines, sin hub | M | Media (tabla de entrada) | UX de credenciales; refresco y batería |
| 5 | **Acciones seguras y auditables** (restart de unidad, limpiar caché) | Ver el problema y no poder tocarlo | Rompe "sólo GET"; exigiría allow-list, confirmación y auditoría | L | Media | Un fallo de diseño aquí destruye la propuesta de seguridad |
| 6 | **Widgets Android + notificaciones** | Ver el estado sin abrir la app | Consume la API actual; Glance + canal propio | S–M | Baja (copiable) | Doze; widgets pobres si no hay multi-host |
| 7 | **Estado del portátil** (batería, térmica, suspensión, consumo) | El portátil también es un host | `/sys/class/power_supply` + `hwmon`; `sysinfo` 0.39 no expone batería, hay que leerlo | S–M | Media-baja | Sensores dispares; poca demanda fuera de devs |
| 8 | **Capacidad: «te quedan N días»** | El disco lleno es el incidente que más se ve venir [E31] | Regresión sobre los 30 días de rollup que ya se guardan | S | Media | Predicción mala con cargas a ráfagas |
| 9 | **Logs bajo demanda** | «¿Por qué se cayó?» sin SSH | journald por unidad, tail acotado y filtrado; el agente ya corre con `ProtectSystem` | M | Baja | Privacidad y tamaño; el sandbox limita el acceso |
| 10 | **Onboarding VPS en 60 s** (`vitals setup`) | En un VPS sin Tailscale el agente no es alcanzable: bind por dirección, nunca `0.0.0.0` | Detectar Tailscale, instalarlo o guiar el `tailscale up`, y emitir el QR al final | S | Baja | Depende de un tercero; permisos de root |

**Estado (0.3.1, publicado):** el candidato #7 ya está implementado:
`/metrics` y `/stream` exponen batería, AC, consumo, salud, ciclos y tiempo
restante; `/host` añade `form_factor` y `virtualized`; el historial guarda
`battery_pct` y `power_w`; la app muestra una tarjeta de energía y oculta el
bloque en hosts sin batería. Quedan fuera de esta entrega la alerta de batería
baja, los diagnósticos de batería y la detección de suspensión/reanudación.

**Estado (0.3.2, en preparación):** se cubre el caso VPS con el candidato #9
llevado a sesiones y autenticación: sesiones activas, accesos aceptados, fallos,
IPs de origen y configuración de `sshd`, con diagnósticos accionables. No es un
visor de logs completo ni incluye `btmp`, geoIP o fail2ban.

## Las 3 apuestas

### Apuesta 1 — «Avisos que llegan» (alertas sin cuenta)

- **Promesa:** el móvil te avisa de que el disco se llena o de que el host se cayó, sin que nadie más se entere.
- **Por qué ahora:** el producto es un visor y un visor no retiene [E29]; el histórico ya permite predecir; ntfy/UnifiedPush hacen viable el push sin FCM ni cuenta [E25].
- **Qué se corta para que quepa:** acciones, logs, multi-host, compartir, iOS. Una sola entrega, dos tipos de aviso (capacidad y host caído).
- **Métrica:** ≥1 regla activa a 7 días en 40 % de instalaciones; entrega p95 <60 s; <2 % de alertas duplicadas.
- **El "ufff":** «Me avisó el jueves de que al SSD le quedaban 4 días y me dio tiempo a mover 200 GB.»

### Apuesta 2 — «Todos tus hosts en una pantalla»

- **Promesa:** tus tres máquinas, un vistazo, sin hub ni cuenta.
- **Por qué ahora:** un homelab típico tiene 2–8 máquinas (hipótesis a validar) y hoy el techo es uno; el coste es de cliente, no de servidor, así que no toca el modelo de seguridad.
- **Qué se corta:** histórico agregado entre hosts, correlación, iOS, sincronización entre dispositivos.
- **Métrica:** ≥2 hosts en 30 % de instalaciones a 30 días; retención D30 +10 pp; widget instalado en 25 %.
- **El "ufff":** «Abrí el widget y vi que el NAS estaba al 98 % de RAM sin abrir nada.»

### Apuesta 3 — «Deja que lo vea mi hermano» (compartir por QR)

- **Promesa:** das acceso temporal, de sólo lectura y con caducidad a un host, sin crear cuentas ni compartir tu tailnet.
- **Por qué ahora:** QR, TTL y tokens por dispositivo ya existen; Tailscale comparte nodos enteros, no una vista acotada [E23]; los competidores exigen exponer el hub o dar credenciales [E2][E18].
- **Qué se corta:** permisos por métrica, edición, transferencia de propiedad, más de un invitado por host.
- **Métrica:** 10 % de hosts compartidos al menos una vez en 90 días; 0 incidentes de alcance; instalar en la máquina de otra persona en <10 min (medido en 5 sesiones).
- **El "ufff":** «Le mandé un QR a mi padre, vio su NAS desde su móvil y a las 8 horas dejó de verlo solo.»

**Por qué no son otras.** El watcher de servicios (#2) es la siguiente capa lógica, pero Beszel ya lo cubre y no diferencia en móvil; va en la fase 3. Las acciones (#5) son las más pedidas y las más peligrosas: no antes de tener alertas, auditoría y un modelo de escritura probado. El portátil (#7) es barato y entra en la fase 2 como acompañante, no como apuesta.

## Contrato de mutación y permisos

Todo lo que no sea lectura pasa por **un único endpoint**, no por quince rutas.

```
POST /api/v1/command
Authorization: Bearer <device_id>.<secret>        (scope operator)
{ "v": 1, "id": "<uuid>", "op": "<enum>", "args": { ... } }
→ {"ok": true,  "id": "...", "result": {...}}
→ {"ok": false, "id": "...", "error": {"code": "schema|capability|denied", "detail": "..."}}
```

Garantías: `op` y `args` con esquema cerrado (`deny_unknown_fields`), ≤4 KB, ≤20 reglas, 60/min por dispositivo, `id` deduplicado 24 h, todos los intentos (aceptados y rechazados) en la tabla `audit`.

Notas de implementación de 0.2: el manifiesto es TOML (`version = 1`, filas `[[grant]]` con `device`, `cap`, `params`, `approved_at`, `source`), vive en `/etc/vitals/capabilities.toml` (sistema) o `<state_dir>/capabilities.toml` (usuario), es `0644` y sólo lo escribe `vitals caps`. `exec.*` se expone como operación **reservada**: se autoriza por scope + concesión y responde `501 not_implemented` sin ejecutar nada, para poder verificar el modelo de permisos antes de v0.4. `diagnostics.get` y `env.suggest` son lecturas y se sirven como `GET`, accesibles también a un `reader`. Las reglas de alerta se guardan con `alerts.set` (reemplazo del conjunto, ≤20, `id` estable; una definición idéntica conserva la histéresis), se borran con `alerts.delete` y se silencian con `alerts.silence`. La histéresis es de 2 unidades absolutas y la predicción de disco usa una regresión de 7 días; el historial de eventos y el uso por mount se guardan 30 días.

### Scopes por dispositivo

| Scope | Puede | Cómo se obtiene |
|---|---|---|
| `reader` (defecto) | GET de métricas, procesos e histórico | `vitals pair` |
| `operator` | lo anterior + `command` + `GET /audit` | `vitals pair --operator` |

Los permisos se conceden **por dispositivo**, nunca por host: cada concesión
guarda el `device_id` que la pidió. Un `operator` nuevo no hereda las
concesiones `exec.*` de otro móvil. Los permisos de bajo riesgo vienen con el
scope para no añadir fricción:

| Permiso | `reader` | `operator` | Aprobación en el host |
|---|---|---|---|
| `read.metrics`, `read.processes`, `read.history` | ✅ | ✅ | no |
| `read.audit` | ❌ | ✅ | no |
| `write.alerts`, `write.channels` | ❌ | ✅ | no |
| `exec.*` | ❌ | ✅ (sólo tras aprobación) | **sí, por dispositivo** |

Revocar es también por dispositivo (`vitals caps revoke <device_id> <cap>`), sin
tocar a los demás.

### Catálogo de permisos (cerrado, versionado en código)

| id | Clase | Parámetros | Riesgo | Requiere instalación |
|---|---|---|---|---|
| `read.audit` | lectura | ventana temporal | bajo | no |
| `write.alerts` | escritura | reglas tipadas | bajo | no |
| `write.channels` | escritura | destino validado (https + allow-list o IP privada) | medio | no |
| `exec.unit_restart` | ejecución | unidad de una allow-list | alto | regla polkit |
| `exec.journal_vacuum` | ejecución | tamaño máximo | medio | unidad oneshot |
| `exec.pkg_cache_clean` | ejecución | tamaño máximo | medio | unidad oneshot |

El manifiesto `/etc/vitals/capabilities.toml` lo escribe sólo `vitals caps` como root. La API jamás lo modifica. Cualquier `op` cuya capability no esté en el manifiesto devuelve `403 capability_not_granted` con el id de la solicitud.

### Solicitud y aprobación

1. App → `command {op:"caps.request", args:{cap:"exec.unit_restart", units:["jellyfin.service"]}}`. El servidor valida contra el catálogo y acota parámetros. Antes de enviar, la app muestra operación, parámetros, riesgo y qué habilitará exactamente; sin esa confirmación no hay solicitud.
2. El agente devuelve un **código de 6 caracteres** (TTL 10 min) y una fila en `capability_requests`.
3. En el host, dos vías equivalentes que escriben la misma concesión:
   - `sudo vitals caps pending` lista las solicitudes y `sudo vitals caps approve 2` aprueba la segunda (vía principal);
   - `sudo vitals caps approve A1B2C3` usa el código de la app (respaldo cuando se aprueba desde otra máquina).
   Ambas muestran operación, parámetros y riesgo antes de confirmar.
4. El agente recarga; la app muestra el permiso como concedido, con fecha, origen y dispositivo.

Un atacante con token `operator` no puede inventar permisos: `cap` sólo acepta IDs del catálogo y la concesión es un acto de root en el host.

**Auditoría:** se guardan 90 días o 10 000 filas, lo que llegue antes. Un
`operator` ve la auditoría de todo el host (quién pidió qué y qué se aplicó);
un `reader` no ve nada.

**Historial de alertas:** 30 días, con estado (disparada, entregada, silenciada,
recuperada). Es lo que la app muestra en «Alertas recientes» y lo que permite
distinguir un aviso puntual de un problema crónico.

### Cómo se hace real el privilegio

Las capabilities de Linux son demasiado gruesas. Se usan, en orden de preferencia: unidades oneshot preinstaladas con `ExecStart` fijo, o una regla polkit que autoriza `manage-units` sólo para unidades de una allow-list y sólo al usuario `vitals`. El agente nunca corre como root. Habilitar `exec.*` obliga a dejar de usar `DynamicUser` y pasar a un usuario fijo `vitals`; sin permisos `exec` todo sigue como hoy.

## Roadmap (~6 meses)

| Fase | Entrega | Incluye | **No** incluye |
|---|---|---|---|
| **1. Semanas 1–8** | v0.2 «Alertas y contrato» | `POST /command` con scopes `reader`/`operator`, manifiesto de permisos, auditoría visible; `alerts.set`; watchdog de host caído en la app; token en Keystore | Multi-host, `exec.*`, logs, compartir, widget, iOS |
| **2. Semanas 9–17** | v0.3 «Flota y permisos» | Multi-host, vista global, widget, estado del portátil, `vitals setup` para VPS, pantalla de permisos con solicitud y aprobación, `write.channels` + ntfy self-hosted | `exec.*`, logs, compartir, iOS, histórico agregado |
| **3. Semanas 18–26** | v0.4 «Confianza» | Compartir por QR con alcance y caducidad; `exec.unit_restart` y `exec.*_clean` con polkit/oneshot **sólo si las alertas demuestran uso** (≥30 % de instalaciones con una regla activa a 30 días); watcher de servicios; logs acotados | `exec` por defecto, agente root, iOS, cloud, HA, Prometheus, SMS/llamada |

Regla de corte: si una fase se retrasa, se recorta por la derecha (lo último de la lista), nunca se solapan fases. Cada entrega sale con changelog, `smoke.sh` ampliado y una nota de threat model si toca red.

> Estado (noviembre de 2026): de la fase 2 están implementadas dos rebanadas:
> «estado del portátil» (v0.3.1) y «accesos SSH» (v0.3.2). Multi-host,
> widget, `vitals setup`, `write.channels` y la aprobación de permisos desde el
> móvil siguen pendientes.

## MVP de la próxima versión (v0.2.0 «Alertas»)

Una sola entrega: **que el móvil avise de capacidad y de caída sin cuenta ni cloud**.

Alcance: `POST /api/v1/command` con esquema cerrado y scopes `reader`/`operator`; manifiesto de permisos y `GET /api/v1/audit`; `op: alerts.set` para disco (% y días restantes), memoria, temperatura y silencio; evaluador en el agente con histéresis y deduplicación; watchdog en la app con **servicio en primer plano** y SSE para host caído (icono permanente sólo mientras la vigilancia está activa; un job de WorkManager cada 15 min lo revive si el sistema lo mata); `diagnostics.get` + `env.suggest` (hallazgos estructurados y el comando exacto que los arreglaría, sin ejecutarlo); historial de alertas de 30 días; migración del token a Keystore; pantalla de reglas con 3 plantillas y pantalla de permisos con estado concedido/pendiente por dispositivo y el riesgo visible antes de solicitar. ntfy entra en la siguiente entrega como `write.channels`, **self-hosted en loopback como canal por defecto** (ntfy.sh sólo como respaldo documentado).

**Criterios de aceptación verificables**

1. Crear la regla "disco >85 % o <7 días" desde la app en ≤3 toques y verla persistida tras reiniciar el agente y tras `vitals-update`.
2. Con ntfy self-host en LAN (IP o DNS local), 20 eventos sintéticos entregan p95 <60 s **sin cambiar** la política de egress del unit (la LAN ya está permitida).
3. Con ntfy público, el instalador genera un drop-in de egress, la app marca "internet: activada" y el threat model documenta qué sale y a dónde. (siguiente entrega)
4. Matar el agente: notificación de "host caído" en <45 s (dos keepalives SSE perdidos, keepalive de 15 s); al volver, una sola notificación de recuperación; 0 duplicados en 24 h. Con el servicio en primer plano activo y pantalla apagada.
5. Histéresis: oscilar ±2 % alrededor del umbral no genera más de 1 alerta; silencio de 1/8/24 h suprime y luego reevalúa.
6. `POST /api/v1/command` rechaza >4 KB, `op` desconocida, campos desconocidos y valores fuera de rango; un test enumera rutas y falla si aparece cualquier método distinto de GET salvo `pair` y `command`. Un token `reader` recibe 403 en `command`; uno `operator` sin manifiesto recibe `capability_not_granted`.
6b. Un `exec.*` aprobado al dispositivo A devuelve 403 al dispositivo B; revocar en A no afecta a B. Aprobar por lista y por código produce la misma fila de concesión.
6c. La auditoría recorta a 90 días o 10 000 filas; `operator` la ve completa y `reader` recibe 403.
7. Predicción: con una serie sintética de llenado lineal, el error de "días restantes" es <20 %; con serie plana no se alerta.
8. El token deja de estar en `SharedPreferences`: cifrado con Keystore, migración automática sin reemparejar; test en dispositivo real.
9. Consumo: <5 % de batería en 8 h con el watchdog activo y pantalla apagada (Battery Historian en un dispositivo físico).
10. Verificación completa en verde: `cargo test`, `cargo clippy --all-targets`, `scripts/smoke.sh` ampliado con el flujo de alertas, `./gradlew assembleDebug`, y las 4 pruebas manuales del `TESTING.md` actualizadas.

## No-objetivos (6 meses)

iOS; hub o cuenta en la nube; ingesta Prometheus/OTel; agente como root; ejecución de comandos arbitrarios; control de unidades fuera de la allow-list; socket de Docker; agente para Windows/macOS; multi-tenant u organización; páginas de estado públicas; SNMP, Kubernetes; alertas por SMS o llamada; monetización con datos.

## Riesgos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Sin cuenta no hay SaaS ni ingreso claro | Sostenibilidad | Ser explícito: OSS con coste marginal cero; explorar soporte/consultoría o build de pago, nunca datos |
| Las alertas exigen egress y rompen "sin internet" | Credibilidad del modelo | Opt-in explícito, indicador en la app, ntfy en LAN como camino sin egress, threat model versionado |
| Depender de que el usuario monte ntfy | Activación baja | Plantillas con ntfy.sh y topic secreto como opción fácil; documentar el matiz "sin cuenta ≠ sin terceros" |
| Doze y fabricantes matan el watchdog | Alertas perdidas | Servicio en primer plano visible + job de WorkManager que lo revive; exclusión de batería opcional; estado "vigilando" comprobable |
| El icono permanente molesta y lleva a desinstalar | Retención | El servicio sólo existe mientras hay reglas activas o el usuario activa "vigilar"; texto de la notificación corto y sin datos sensibles |
| Política de Google Play si algún día se publica | Distribución | Servicio declarado como monitorización del propio dispositivo del usuario; APK por releases como canal principal |
| El VPS sin Tailscale no es alcanzable | Onboarding roto en el caso #2 | `vitals setup` en fase 2; mientras tanto, decirlo en la primera pantalla |
| Beszel publica app oficial | Se estrecha el hueco | Doblar en móvil y en compartir por QR, que no tienen |
| Un solo mantenedor, Android only | Velocidad y TAM | Alcance por fases, releases pequeñas, no prometer iOS |
| Webhook URL como secreto en SQLite | Fuga de credencial | Fichero 0600, rotación desde la app, no loguear URLs, límite de tamaño |
| La nueva superficie `command` erosiona "sólo GET" | Se pierde el argumento de seguridad | Esquema cerrado, scopes, manifiesto, test que falla ante cualquier método nuevo, threat model versionado |
| Fatiga de aprobación: cada permiso exige root en el host | El usuario no habilita nada | Tres plantillas con un clic, códigos de 6 caracteres, aprobación en lote si es seguro |
| Regla polkit demasiado amplia | Escalada de privilegios | Generarla desde una allow-list, test que la valida, nunca comodines |

## Evidencia

- [E1] Beszel, qué es y features — https://beszel.dev/guide/what-is-beszel
- [E2] Beszel, apps móviles **no oficiales** — https://beszel.dev/guide/third-party-integrations/mobile-apps
- [E3] Beszel, notificaciones vía Shoutrrr (ntfy, Telegram…) — https://beszel.dev/guide/notifications
- [E4] Beszel, instalación del hub y del agente — https://beszel.dev/guide/hub-installation
- [E5] Netdata, precios: Community ≤5 nodos, Business desde $4,50/nodo/mes — https://www.netdata.cloud/pricing
- [E6] Netdata Mobile exige cuenta de Netdata Cloud — https://learn.netdata.cloud/docs/alerts-%26-notifications/notifications/centralized-cloud-notifications/netdata-mobile-app
- [E7] Netdata Mobile en Play: 2,1★, 88 reseñas — https://play.google.com/store/apps/details?id=cloud.netdata.android
- [E8] Glances: Python, API sin auth por defecto, web — https://github.com/nicolargo/glances
- [E9] node_exporter: un exporter por host, sin histórico propio — https://github.com/prometheus/node_exporter
- [E10] Grafana Cloud: free 10k series/14 días, Pro $19 + uso — https://grafana.com/pricing
- [E11] App de Grafana: requiere Cloud y no soporta OSS — https://grafana.com/docs/grafana-cloud/platform/mobile-app y https://play.google.com/store/apps/details?id=com.grafana.oncall.prod
- [E12] Uptime Kuma: checks y notificaciones, sin métricas de host — https://github.com/louislam/uptime-kuma
- [E13] Uptime Kuma: lista de apps de terceros (no hay app oficial) — https://github.com/louislam/uptime-kuma/wiki/3rd-Party-Addons-Apps
- [E14] Uptime Kuma: el socket de Docker da control total — https://github.com/louislam/uptime-kuma/wiki/How-to-Monitor-Docker-Containers
- [E15] Home Assistant: push por FCM por defecto, local push como alternativa — https://companion.home-assistant.io/docs/data-handling/
- [E16] Home Assistant: arquitectura de las apps companion — https://contributors.home-assistant.io/architecture/platform/companion-apps/
- [E17] Cockpit vs Portainer: alcance y límites — https://oneuptime.com/blog/post/2026-03-20-portainer-vs-cockpit/view
- [E18] Portainer no tiene app móvil oficial — https://onepilotapp.com/blog/portainer-mobile-app
- [E19] Dozzle: logs en vivo, 7 MB, sin histórico — https://github.com/amir20/dozzle
- [E20] Proxmox VE Companion: sólo PVE, VMs/LXC — https://pve.proxmox.com/wiki/Proxmox_VE_Mobile
- [E21] Proxmox en Play: 3,9★, 100K+ descargas — https://play.google.com/store/apps/details?id=com.proxmox.app.pve_flutter_frontend
- [E22] Termius: Starter gratis, Pro $10/mes anual, sync en la nube — https://termius.com/pricing
- [E23] Tailscale: Personal $0 (6 usuarios), Standard $8/usuario — https://tailscale.com/pricing
- [E24] Tailscale: métricas de cliente vía Prometheus, no métricas del host — https://tailscale.com/docs/reference/tailscale-client-metrics
- [E25] ntfy: self-host, FCM sólo en ntfy.sh, UnifiedPush — https://docs.ntfy.sh/subscribe/phone y https://unifiedpush.org/users/distributors/ntfy
- [E26] r/selfhosted: Netdata «difficult to navigate using a phone» — https://www.reddit.com/r/selfhosted/comments/1ggdgdy/replacement_for_netdata
- [E27] HN: «mis agentes de monitorización 24/7 son mi mujer y mis hijos» — https://news.ycombinator.com/item?id=36225239
- [E28] HN: «Grafana ocupa gigabytes que mi VPS no tiene» — https://news.ycombinator.com/item?id=42145582
- [E29] «Los monitores sin notificaciones son un dashboard que olvidas abrir» — https://techzei.com/uptime-kuma-setup-guide
- [E30] Beszel self-hosted en un plan de $5/mes — https://deployable.sh/apps/beszel/pricing/
- [E31] r/homelab: el coste de montar y mantener el stack de monitorización — https://www.reddit.com/r/homelab/comments/1qa0xx9/is_server_monitoring_actually_to_heavy_for_small
- [E32] KumaAlert: app nativa de pago para un monitor self-hosted — https://kumaalert.app/uptime-kuma-app
