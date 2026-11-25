# API v1

Base: `https://<endpoint>:8443`. Todo es JSON UTF-8 salvo el stream (SSE).
Las unidades son explícitas en los sufijos: `_bps` bytes/segundo, `_bytes`
bytes absolutos, `_celsius` grados, `_wh` vatios-hora, `_w` vatios, `_v`
voltios, `t` segundos UNIX.

Autenticación, salvo donde se indique:

```
Authorization: Bearer <device_id>.<secret>
```

Cada dispositivo tiene un **scope** fijado al emparejarse:

| Scope | Puede | Se obtiene con |
|---|---|---|
| `reader` (defecto) | Todos los `GET` de métricas, procesos, histórico y accesos | `vitals pair` |
| `operator` | Lo anterior + `POST /command` + `GET /audit` | `vitals pair --operator` |

Un `reader` que llame a una ruta de `operator` recibe `403` (y el intento de
`command` queda auditado). Las capabilities de alto riesgo (`exec.*`) no las
concede el scope: exigen una fila en el manifiesto del host.

Errores: `{"error": "mensaje"}` con el status correspondiente.
`401` token ausente o inválido · `403` scope insuficiente o capability no
concedida · `429` rate limit (240 req/min por IP, 5 intentos de pairing/min,
60 comandos/min por dispositivo) · `400` parámetro inválido · `413` cuerpo
demasiado grande · `503` colector arrancando · `504` timeout de handler (20 s).

---

## GET /healthz

Sin autenticación y sin datos del host. Sirve para liveness y para descubrir
la versión.

```json
{ "ok": true, "version": "0.1.0", "uptime_s": 412 }
```

## POST /api/v1/pair

Sin autenticación. Rate limit 5/min por IP. El código es de un solo uso y
caduca en 5 minutos.

```json
// request
{ "code": "8AYG-P5P0", "device_name": "pixel" }
// response 200
{ "device_id": "3f9a1c0b7d2e", "token": "3f9a1c0b7d2e.9e4c...64hex", "scope": "reader" }
```

El scope lo fija el código emitido en el host (`vitals pair [--operator]`); el
cuerpo de la petición no puede pedirlo. Acepta el código con o sin guion, en
minúsculas, y corrige las confusiones OCR habituales (`O` → `0`, `I`/`L` → `1`).

Errores: `401 invalid or expired code`, `403 device limit reached` (16),
`400 malformed code`.

## GET /api/v1/host

Información estática del host. Cacheable en la app.

```json
{
  "hostname": "steampunker",
  "os": "Ubuntu 26.04 LTS",
  "kernel": "6.14.0-31-generic",
  "arch": "x86_64",
  "boot_time": 1795000000,
  "cpu_model": "AMD Ryzen 7 5825U with Radeon Graphics",
  "cores": 16,
  "mem_total": 16777216000,
  "form_factor": "laptop",
  "virtualized": false
}
```

`form_factor` es `laptop`, `desktop`, `server`, `vm` o `unknown`. Se deduce
primero de la virtualización, después del chasis SMBIOS y por último de la
presencia de una batería. `virtualized` es `true` si se detectó el flag
`hypervisor` o un fabricante de VM conocido.

## GET /api/v1/metrics

Última muestra. `503` si el colector aún no arrancó (el primer tick ocurre
inmediatamente, así que en la práctica no se ve).

```json
{
  "t": 1795471954,
  "cpu": { "total": 12.4, "cores": [8.1, 21.0], "load1": 0.42, "load5": 0.31, "load15": 0.28 },
  "mem": { "total": 16777216000, "used": 9412210688, "available": 7100000000, "swap_total": 0, "swap_used": 0 },
  "net": {
    "rx_bps": 12345, "tx_bps": 678,
    "ifaces": [{ "name": "enp2s0", "rx_bps": 12345, "tx_bps": 678 }]
  },
  "disks": [
    { "mount": "/", "fs": "ext4", "total": 500000000000, "used": 210000000000, "r_bps": 0, "w_bps": 1024 }
  ],
  "temps": [{ "label": "Tctl", "celsius": 52.0 }],
  "access": {
    "sessions": [
      { "user": "root", "tty": "pts/0", "service": "sshd", "remote": true, "host": "203.0.113.9", "since": 1795470000 }
    ],
    "logins_24h": 4,
    "failures_24h": 37,
    "last_login": { "t": 1795470000, "ok": true, "user": "root", "host": "203.0.113.9", "method": "publickey", "port": 58635, "invalid_user": false },
    "last_failure": { "t": 1795469000, "ok": false, "user": "admin", "host": "203.0.113.5", "method": "password", "port": 41102, "invalid_user": true },
    "password_auth": true,
    "config_read": true,
    "root_login": "prohibit-password",
    "source": "journal+auth.log"
  },
  "power": {
    "ac_online": true,
    "on_battery": false,
    "percent": 96.0,
    "status": "charging",
    "power_w": 5.2,
    "seconds_left": null,
    "seconds_to_full": 9300,
    "health_pct": 84.2,
    "cycles": 1193,
    "batteries": [
      {
        "name": "BAT0",
        "present": true,
        "status": "charging",
        "percent": 96.0,
        "power_w": 5.2,
        "energy_now_wh": 36.44,
        "energy_full_wh": 37.88,
        "energy_full_design_wh": 45.01,
        "voltage_v": 12.42,
        "seconds_left": null,
        "seconds_to_full": 9900,
        "health_pct": 84.2,
        "cycles": 1193,
        "technology": "Li-poly",
        "model": "5B10X025"
      }
    ]
  },
  "uptime": 123456
}
```

Notas de semántica:

- `cpu.total` y `cpu.cores` son **porcentaje 0..100**, no fracción.
- `*_bps` son deltas por intervalo, no contadores acumulados. Un contador
  que se reinicia no produce negativos.
- Interfaces `lo`, `docker*`, `veth*`, `br-*` y similares se excluyen.
  `tailscale0` sí se incluye.
- Filesystems `tmpfs`, `overlay`, `squashfs` y similares se excluyen.
- `temps` puede venir vacío si no hay `hwmon` legible.
- `power` **no aparece** en hosts sin batería legible (servidores, VPS,
  escritorios). Los campos opcionales pueden ser `null`.
- `access` puede faltar en el primer segundo, antes del primer refresco de
  accesos. En un host sin sesiones ni registros legibles sigue apareciendo, con
  `sessions` vacío y `source: "none"`. `sessions` lista sesiones activas de
  systemd-logind (o `utmp` como respaldo), incluidas las de consola.
- `failures_24h` incluye intentos con usuario inexistente. `last_login` y
  `last_failure` son el evento más reciente de cada tipo dentro de la ventana
  de siete días. Si `config_read` es `false`, `password_auth` y `root_login` son
  valores por defecto, no lo que dice `sshd`.
- `status` se normaliza a `charging`, `discharging`, `full`, `not_charging` o
  `unknown`; no se reenvía el texto crudo del driver.
- `power_w` es la potencia instantánea de la batería: consumo mientras
  descarga, potencia de carga mientras carga. Se calcula con `power_now` o,
  si falta, `current_now × voltage_now`.
- `seconds_left` y `seconds_to_full` sólo se rellenan en el estado que
  corresponde. Con varias baterías, el porcentaje, la salud y los tiempos se
  ponderan por capacidad; `cycles` es el máximo.
- La energía se normaliza a Wh aunque el driver exponga `charge_*` en µAh:
  el agente aplica el voltaje del paquete.

## GET /api/v1/stream

`text/event-stream`. Un `event: snapshot` por segundo con el mismo objeto que
`/metrics`. Keepalive cada 15 s.

```
event: snapshot
data: {"t":1795471954,"cpu":{...},...}

event: resync
data: {}
```

`resync` llega cuando el cliente se atrasó y perdió eventos (buffer de 8).
La app debe refrescar el estado completo; no hay replay.

## GET /api/v1/history?range=1h

| range | tabla | step |
|---|---|---|
| `15m` | raw (10 s) | 10 |
| `1h` | raw | 15 |
| `6h` | raw | 30 |
| `24h` / `1d` | 1 min | 60 |
| `7d` / `1w` | 1 min | 300 |
| `30d` | 1 min | 1800 |

```json
{
  "range": "6h",
  "step": 30,
  "from": 1795450354,
  "to": 1795471954,
  "points": [
    { "t": 1795450354, "cpu": 11.2, "mem": 9412210688, "net_rx": 1200, "net_tx": 80, "disk_r": 0, "disk_w": 512, "battery_pct": 96.0, "power_w": 5.2 }
  ]
}
```

`mem` es memoria usada en bytes. Cada punto es el promedio del intervalo.
Rango desconocido → `400`.

`battery_pct` y `power_w` son opcionales: se omiten en hosts sin batería y en
las filas anteriores a la migración de 0.3. La app los trata como `null` y no
rompe la gráfica; el promedio de un intervalo ignora las muestras sin dato.

## GET /api/v1/processes?sort=cpu|mem&limit=10

`limit` se recorta a 1..50. La lista se cachea 2 s del lado del servidor.

```json
{
  "sort": "cpu",
  "processes": [
    { "pid": 4242, "name": "firefox", "cpu": 31.5, "mem": 812345678 }
  ]
}
```

`cpu` es porcentaje por proceso (puede superar 100 en multinúcleo).

## GET /api/v1/info

Estado del agente y dispositivos emparejados. Útil para refrescar endpoints
o mostrar el pin actual.

```json
{
  "name": "steampunker",
  "version": "0.3.1",
  "pin": "RZPKDkL9mRhkkObOA54Hpw",
  "endpoints": ["https://192.168.1.104:8443", "https://100.71.72.48:8443"],
  "devices": [
    {
      "id": "3f9a1c0b7d2e",
      "name": "pixel",
      "scope": "operator",
      "created_at": 1795471000,
      "last_seen_at": 1795471900
    }
  ],
  "caps_manifest": "/etc/vitals/capabilities.toml"
}
```

`caps_manifest` es la ruta exacta desde la que el agente lee las concesiones;
`vitals caps` escribe ese mismo fichero.

## POST /api/v1/command

Scope `operator`. Es el **único** endpoint mutable. Todo lo demás sigue siendo
`GET`, y un test enumera las rutas para fallar si aparece otro método.

```json
// request
{
  "v": 1,
  "id": "2f6f9a52-8c17-4b6e-9d0d-2a1f4c7e9b11",
  "op": "caps.request",
  "args": { "cap": "exec.unit_restart", "units": ["jellyfin.service"] }
}

// 200
{ "ok": true, "id": "2f6f9a52-...", "result": { "request_id": 3, "cap": "exec.unit_restart", "risk": "high", "code": "7KQ2M9", "expires_at": 1795472554, "params": { "units": ["jellyfin.service"] } } }

// 4xx
{ "ok": false, "id": "2f6f9a52-...", "error": { "code": "capability_not_granted", "detail": "exec.unit_restart was not approved for this device" } }
```

Garantías:

- Esquema cerrado en el sobre y en cada `args` (`deny_unknown_fields`): un campo
  desconocido, una `op` desconocida o `v != 1` devuelven `400 schema`.
- Cuerpo ≤ 4 KB (`413 too_large`), `id` de 1..64 caracteres `[A-Za-z0-9_-]`.
- 60 comandos/min por dispositivo, independientes de la IP.
- `id` idempotente 24 h: un reintento con el mismo `id` devuelve el resultado
  almacenado sin volver a ejecutar. Los rechazos no se almacenan.
- Todos los intentos van a `audit`, incluidos los rechazos por scope, esquema o
  capability.

Códigos de error: `schema`, `capability_not_granted`, `denied`,
`not_implemented`, `rate_limited`, `too_large`, `internal`.

### Operaciones de 0.2

| `op` | `args` | Resultado | Capability |
|---|---|---|---|
| `caps.request` | `{cap, units?}` o `{cap, max_bytes?}` | Solicitud pendiente + código de 6 caracteres (TTL 10 min) | ninguna (sólo operator) |
| `alerts.set` | `{rules: [...]}` (≤20) | Reglas guardadas | `write.alerts` |
| `alerts.delete` | `{id}` | `{deleted}` | `write.alerts` |
| `alerts.silence` | `{minutes}` (1..1440) | Silencio activo + reglas | `write.alerts` |
| `exec.unit_restart` | `{unit}` | `501 not_implemented` tras autorizar | `exec.unit_restart` + unidad en la allow-list |
| `exec.journal_vacuum` | `{max_bytes}` | `501 not_implemented` tras autorizar | `exec.journal_vacuum` + máximo ≤ concesión |
| `exec.pkg_cache_clean` | `{max_bytes}` | `501 not_implemented` tras autorizar | `exec.pkg_cache_clean` + máximo ≤ concesión |

`exec.*` está **reservado**: en 0.2 sólo se autoriza para poder verificar que
la concesión es por dispositivo, y nunca se ejecuta nada. `write.alerts` viene
con el scope `operator`; no necesita manifiesto.

### Reglas de alerta

`alerts.set` sustituye el conjunto completo. Una regla ausente se elimina; una
regla con el mismo `id` y la misma definición conserva su estado e histéresis.
`id` es estable en el cliente (1..32 caracteres `[a-z0-9_-]`) y hay un máximo de
20 reglas.

```json
{
  "v": 1,
  "id": "c1f2...",
  "op": "alerts.set",
  "args": {
    "rules": [
      { "id": "disk-root", "kind": "disk", "mount": "/", "percent": 85, "days": 7 },
      { "id": "mem", "kind": "memory", "percent": 90 },
      { "id": "cpu-temp", "kind": "temperature", "celsius": 95 }
    ]
  }
}
```

| `kind` | Campos | Condición de disparo |
|---|---|---|
| `disk` | `mount?` (por defecto: el peor filesystem), `percent?`, `days?` (al menos uno) | `used % > percent` o `days_left < days` |
| `memory` | `percent` | `used % > percent` |
| `temperature` | `celsius` | cualquier sensor `> celsius` |

Los campos que no pertenecen al `kind` se rechazan con `400 schema`, igual que
un `id` duplicado o más de 20 reglas.

**Histéresis:** se dispara al cruzar el umbral y sólo se recupera cuando el
valor lo cruza de vuelta por 2 unidades (2 puntos porcentuales, 2 días o 2 °C).
Así, oscilar alrededor del umbral produce un único evento `fired`.

**Predicción de disco:** cada minuto se guarda el uso por mount durante 30 días.
`days_left` es la regresión lineal del uso en los últimos 7 días; una serie
plana o que decrece no predice nada y no alerta. Se necesitan al menos 10
muestras y una hora de historia.

**Silencio:** `alerts.silence` abre una ventana (1..1440 minutos), resetea el
estado de disparo y registra un evento `silenced` por cada regla que estaba
disparada. Durante la ventana no se generan eventos; al terminar, una regla que
siga mal vuelve a disparar una vez.

El historial se conserva 30 días y sobrevive a reinicios y a `vitals-update`
(SQLite en el directorio de estado).

`caps.request` no concede nada: crea una fila pendiente y muestra el código que
el operador del host debe aprobar. Sólo acepta capabilities del catálogo
marcadas como aprobables (`exec.*`). Una nueva solicitud para el mismo
dispositivo y capability invalida la anterior.

### Aprobación de capabilities

El manifiesto lo escribe únicamente `vitals caps` (root en instalación de
sistema, propietario del servicio en instalación de usuario). El agente nunca
lo modifica.

```bash
sudo vitals caps pending              # solicitudes con riesgo y parámetros
sudo vitals caps approve 1            # por número de la lista
sudo vitals caps approve 7KQ2M9       # por el código que muestra la app
sudo vitals caps list                 # concesiones vigentes
sudo vitals caps revoke <device_id> exec.unit_restart
```

Ambas formas de aprobar escriben la misma fila de concesión; sólo cambia el
campo `source` (`list` o `code`). La concesión es por `device_id`: aprobar en A
no da nada a B, y revocar en A no toca a B.

```toml
# /etc/vitals/capabilities.toml
version = 1

[[grant]]
device = "3f9a1c0b7d2e"
cap = "exec.unit_restart"
params = { units = ["jellyfin.service"] }
approved_at = 1795472000
source = "list"
```

El fichero es `0644` (sin secretos) para que el agente, que corre con
`DynamicUser`, pueda leerlo. Un manifiesto ausente no concede nada; uno
corrupto se ignora por completo (fail closed) y el CLI se niega a sobrescribirlo.

## GET /api/v1/audit

Scope `operator`; un `reader` recibe `403`. Devuelve los intentos de `command`
del más reciente al más antiguo.

```
GET /api/v1/audit?limit=100&before=812
```

`limit` se recorta a 1..500 (por defecto 100). `before` es paginación por `id`.

```json
{
  "entries": [
    {
      "id": 812,
      "at": 1795472100,
      "device_id": "3f9a1c0b7d2e",
      "op": "exec.unit_restart",
      "request_id": "2f6f9a52-...",
      "outcome": "rejected",
      "code": "capability_not_granted",
      "detail": "exec.unit_restart was not approved for this device"
    }
  ],
  "retention_days": 90,
  "max_rows": 10000
}
```

`outcome` es `accepted`, `rejected` o `replayed`. `detail` se recorta a 200
caracteres y nunca contiene tokens. Retención: 90 días o 10 000 filas, lo que
llegue antes.

## GET /api/v1/alerts

Scope `operator`. Devuelve reglas, historial de 30 días y predicciones de disco.

```
GET /api/v1/alerts?limit=50&before=312
```

```json
{
  "rules": [
    {
      "id": "disk-root",
      "kind": "disk",
      "mount": "/",
      "percent": 85.0,
      "days": 7.0,
      "celsius": null,
      "silence_until": 0,
      "firing": true,
      "since": 1795472000
    }
  ],
  "events": [
    {
      "id": 312,
      "rule_id": "disk-root",
      "kind": "disk",
      "at": 1795472000,
      "state": "fired",
      "value": 86.4,
      "threshold": 85.0,
      "detail": "disk on /: 86.4% used, 6.2 days left"
    }
  ],
  "predictions": [{ "mount": "/", "days_left": 6.2 }],
  "retention_days": 30
}
```

`events` va del más reciente al más antiguo, paginado por `id` con `before`.
`state` es `fired`, `recovered`, `silenced` (0.2); `delivered` se añade con los
canales de la siguiente entrega. `predictions` sólo incluye mounts con historia suficiente.

## GET /api/v1/caps

Scope `operator`. Estado de las capabilities **de este dispositivo**, incluido
el código de una solicitud pendiente. Un `reader` recibe `403`.

```json
{
  "device_id": "3f9a1c0b7d2e",
  "scope": "operator",
  "capabilities": [
    { "id": "read.audit", "risk": "low", "approvable": false, "state": "granted", "granted_by": "scope", "params": null, "request": null },
    {
      "id": "exec.unit_restart",
      "risk": "high",
      "approvable": true,
      "state": "pending",
      "granted_by": null,
      "params": { "units": ["jellyfin.service"] },
      "request": { "id": 3, "code": "7KQ2M9", "expires_at": 1795472554 }
    }
  ]
}
```

`state` es `granted`, `pending` o `not_granted`. `granted_by` distingue el scope
(`scope`) de una aprobación en el host (`manifest`). Las capabilities `exec.*`
nunca se conceden por scope.

## GET /api/v1/access?range=24h

Cualquier token. Sesiones activas, accesos aceptados y fallos de autenticación
SSH en la ventana pedida (`24h` por defecto, `7d`). `400` con un `range`
desconocido y `503` antes del primer refresco.

```json
{
  "updated": 1795472100,
  "range": "24h",
  "window_s": 86400,
  "source": "journal+auth.log",
  "covered_s": 518400,
  "sessions": [
    { "user": "root", "tty": "pts/0", "service": "sshd", "remote": true, "host": "203.0.113.9", "since": 1795470000 }
  ],
  "logins": [
    { "t": 1795470000, "ok": true, "user": "root", "host": "203.0.113.9", "method": "publickey", "port": 58635, "invalid_user": false }
  ],
  "failures": [
    { "t": 1795469000, "ok": false, "user": "admin", "host": "203.0.113.5", "method": "password", "port": 41102, "invalid_user": true }
  ],
  "sources": [
    { "host": "203.0.113.9", "public": true, "ok": 4, "failed": 0, "first_t": 1794984900, "last_t": 1795470000, "users": ["root"], "new": false }
  ],
  "ssh": {
    "password_auth": true,
    "root_login": "prohibit-password",
    "files": ["/etc/ssh/sshd_config.d/50-cloud-init.conf", "/etc/ssh/sshd_config"]
  },
  "totals": { "logins": 4, "failures": 37, "sessions": 1, "remote_sessions": 1 }
}
```

`source` es `journal`, `auth.log`, `journal+auth.log` o `none`. `covered_s`
indica cuánta historia hay realmente, acotada a siete días, y `totals` cuenta
la ventana completa aunque los arrays se recorten a los 100 eventos más
recientes. `sources` agrupa por dirección de origen y también se recorta a 100.
`new` marca una IP cuyo primer evento cae dentro de las últimas 24 h y sólo se
calcula cuando la cobertura supera un día, para no señalar como nueva una IP
antigua que el registro ya no alcanza. El journal se lee con un tope de 20 000
líneas SSH; por encima de eso, `covered_s` y los totales pueden quedarse cortos
en un host bajo un ataque muy intenso.

El agente lee `journalctl -u ssh -u sshd --since -7d` y `/var/log/auth.log`
(más `.1` y `/var/log/secure` si existen). Los duplicados entre ambas fuentes
se colapsan. Las sesiones salen de `/run/systemd/sessions` y, si no hay
systemd, de `/run/utmp`.

Para que el servicio vea los registros necesita `adm` y `systemd-journal`; el
`vitals.service` de ejemplo ya los añade en `SupplementaryGroups=`. Sin ellos
la respuesta sigue siendo válida, con `source: "none"`, y el diagnóstico avisa
con `ssh.logs_unreadable`.

## GET /api/v1/diagnostics

Cualquier token. Hallazgos estructurados; no modifica nada.

```json
{
  "generated_at": 1795472100,
  "findings": [
    {
      "id": "disk.pressure:/",
      "severity": "critical",
      "title": "Disco / al 96.2 %",
      "detail": "Quedan 18.4 GiB de 480.0 GiB. Un disco lleno rompe bases de datos, logs y actualizaciones.",
      "evidence": { "mount": "/", "used_pct": 96.2, "used_bytes": 496000000000, "total_bytes": 515000000000 },
      "suggestion": "disk.free_space:/"
    }
  ]
}
```

`severity` es `info`, `warning` o `critical`. Se evalúan: disco, predicción de
capacidad, memoria, temperatura, alcanzabilidad (sólo loopback), permisos del
directorio de estado, ausencia de reglas de alerta y, cuando hay datos de
acceso, autenticación por contraseña, login de root, fallos repetidos y accesos
desde una dirección nueva en las últimas 24 h. `suggestion` enlaza con el
siguiente endpoint.

## GET /api/v1/suggest

Cualquier token. El comando exacto que resolvería cada hallazgo, **sin
ejecutarlo**.

```json
{
  "generated_at": 1795472100,
  "suggestions": [
    {
      "id": "disk.free_space:/",
      "finding": "disk.pressure:/",
      "risk": "low",
      "command": "sudo journalctl --vacuum-size=200M",
      "explanation": "Recupera espacio de logs del sistema en /. Si no basta, inspecciona con: sudo du -xhd1 / | sort -h | tail -20"
    }
  ]
}
```

La app los muestra en Ajustes → Diagnóstico; ni el agente ni el móvil ejecutan
el comando. `risk` es `low`, `medium` o `high`.

---

## Payload del QR

```
vitals1:<base64url(sin padding) de este JSON>
```

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

`name` es el hostname del agente y `dev` la etiqueta del dispositivo que se
está emparejando (`vitals pair --name`). `scope` es informativo para la UI: la
autoridad es la fila del código en el agente, así que editar el QR no eleva un
`reader` a `operator`. Por defecto `ep` sólo trae IPv4; `bind_ipv6 = true`
añade las direcciones IPv6. `pin` = base64url de los primeros 16 bytes de
SHA-256 sobre el DER del certificado. `ep` va ordenado: LAN primero (menor
latencia en casa), Tailscale después. `exp` es segundos UNIX.

## Verificación del certificado en Android

```kotlin
override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
    val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
    val pin = Base64.encodeToString(digest.copyOf(16), Base64.NO_WRAP or Base64.URL_SAFE)
    if (pin != storedPin) throw CertificateException("pin mismatch")
}
```

`chain[0]` es el certificado hoja, que aquí es también la raíz porque es
autofirmado. No validar fechas ni nombres: el pin es la garantía.
