# Handoff — vitals

> **Este archivo ya no es la fuente de verdad.** El handoff canónico, completo y
> actualizado está en **[`NEXT-SESSION.md`](NEXT-SESSION.md)**.
>
> Antes existían dos documentos distintos: `HANDOFF.md` se quedó congelado en la
> época de v0.1.0 y `NEXT-SESSION.md` es el que se mantiene al día. Para evitar
> dos verdades, aquí sólo queda este puntero y un resumen mínimo.

## Estado actual

- Versión publicada: **v0.3.1**.
- Árbol actual: **0.3.1 «portátil»** (batería, consumo, `form_factor` e
  historial de energía), publicado y verificado en local y en el A54.
- Release: https://github.com/luismasuarez/vitals/releases/tag/v0.3.1
- Agente: 0.3.1, servicio de usuario activo, state dir `0700`.
- App: `dev.vitals.app` 0.3.1, emparejada como `operator` en el A54.
- Pendiente: descarga dinámica de energía (desenchufar/enchufar), temperatura en
  0.2.1, WorkManager, batería del móvil y actualización real desde 0.1.4.
- Siguiente entrega prevista: **0.3.2** (`kind: "battery"` + diagnósticos) o
  `write.channels` + ntfy.

Todo el detalle —estado exacto, decisiones cerradas, modelo de permisos,
pruebas realizadas, pendientes, comandos, archivos clave y trampas del
entorno— está en [`NEXT-SESSION.md`](NEXT-SESSION.md).

## Cómo retomar

```bash
cd ~/tools/vitals
export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
systemctl --user is-active vitals
vitals --version
vitals devices list
vitals tls show
adb devices
```

Después, leer `NEXT-SESSION.md` completo antes de tocar código.
