# API v1

Base: `https://<endpoint>:8443`. Todo es JSON UTF-8 salvo el stream (SSE).
Las unidades son explícitas en los sufijos: `_bps` bytes/segundo, `_bytes`
bytes absolutos, `celsius` grados, `t` segundos UNIX.

Autenticación, salvo donde se indique:

```
Authorization: Bearer <device_id>.<secret>
```

Errores: `{"error": "mensaje"}` con el status correspondiente.
`401` token ausente o inválido · `429` rate limit (240 req/min por IP,
5 intentos de pairing/min) · `400` parámetro inválido · `503` colector
arrancando · `504` timeout de handler (20 s).

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
{ "device_id": "3f9a1c0b7d2e", "token": "3f9a1c0b7d2e.9e4c...64hex" }
```

Acepta el código con o sin guion, en minúsculas, y corrige las confusiones
OCR habituales (`O` → `0`, `I`/`L` → `1`).

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
  "mem_total": 16777216000
}
```

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
    { "t": 1795450354, "cpu": 11.2, "mem": 9412210688, "net_rx": 1200, "net_tx": 80, "disk_r": 0, "disk_w": 512 }
  ]
}
```

`mem` es memoria usada en bytes. Cada punto es el promedio del intervalo.
Rango desconocido → `400`.

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
  "version": "0.1.0",
  "pin": "RZPKDkL9mRhkkObOA54Hpw",
  "endpoints": ["https://192.168.1.104:8443", "https://100.71.72.48:8443"],
  "devices": [
    { "id": "3f9a1c0b7d2e", "name": "pixel", "created_at": 1795471000, "last_seen_at": 1795471900 }
  ]
}
```

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
  "ep": ["https://192.168.1.104:8443", "https://100.71.72.48:8443"],
  "pin": "RZPKDkL9mRhkkObOA54Hpw",
  "code": "8AYG-P5P0",
  "exp": 1795471954
}
```

`name` es el hostname del agente y `dev` la etiqueta del dispositivo que se
está emparejando (`vitals pair --name`). `pin` = base64url de los primeros 16
bytes de SHA-256 sobre el DER del certificado. `ep` va ordenado: LAN primero
(menor latencia en casa), Tailscale después. `exp` es segundos UNIX.

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
