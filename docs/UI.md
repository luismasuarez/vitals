# UI y navegación de vitals

> Documento de la app Android (Jetpack Compose). Describe la arquitectura de
> información, el sistema visual y las reglas que deben respetar los cambios
> futuros. Las capturas de verificación viven localmente en
> `.impeccable/review/after/` (ignoradas por git).

## Modo y principio

La app es una superficie **Operate**: el usuario entra a resolver una tarea
(decidir si el host está sano, quién ha entrado, qué aviso está activo), no a
contemplar el producto. La familiaridad y la densidad útil mandan; la expresión
se queda en el detalle.

Regla central: **cada pantalla responde a un tema**. Un bloque que no pertenece
al tema de la pantalla se mueve a su destino o se elimina. Resumen no es un
dashboard que lo contiene todo: es la pantalla de triaje.

## Mapa de navegación

Cinco destinos en la barra inferior. Todo lo demás es una pantalla empujada con
flecha de vuelta y el gesto Atrás del sistema.

| Destino | Tipo | Contenido |
|---|---|---|
| **Resumen** | top-level | Veredicto, alertas/umbrales, CPU/RAM/disco/red, energía y accesos en una línea cada uno. |
| **Recursos** | top-level + sub-tabs | CPU · Memoria · Discos · Red · Energía. Energía sólo aparece si el host reporta batería. |
| **Accesos** | top-level + sub-tabs | Ahora mismo · Registros de acceso · SSH. |
| **Alertas** | top-level | Reglas, riesgo sin regla, plantillas por disco/memoria/temperatura, silencio, vigilancia de caída e historial. |
| **Más** | top-level | Procesos, Diagnóstico, Permisos, Conexión, Ajustes. |
| **Procesos** | detalle | Lista en vivo por CPU o memoria, filtro y actualización. |
| **Diagnóstico** | detalle | Hallazgos y comandos sugeridos; nunca se ejecutan. |
| **Permisos** | detalle | Permisos del token y `exec.*` que requieren aprobación. |
| **Conexión** | detalle | Direcciones, modo de conexión, latencia y reconexión. |
| **Ajustes** | detalle | Host, token, vigilancia (estado), conexión, diagnóstico, dispositivos y zona peligrosa. |

La pila de navegación vive en `AppViewModel`: `stack`, `navigate()`, `back()` y
`canGoBack`. Un destino top-level reemplaza la pila; un detalle se empuja. El
`BackHandler` de `App.kt` cubre el gesto y el botón Atrás.

## Asignación de contenido

| Contenido | Dónde vive | Nivel |
|---|---|---|
| Hostname, OS, kernel, CPU, formato, uptime | Ajustes y encabezado de Más | Contexto |
| CPU total, núcleos, carga, temperaturas | Recursos › CPU | Primario |
| RAM, swap, disponible, procesos por memoria | Recursos › Memoria | Primario |
| Mounts, capacidad, uso, E/S, predicción de llenado | Recursos › Discos | Primario |
| Tráfico, rango 1 h/6 h, interfaces | Recursos › Red | Primario |
| Batería, alimentación, autonomía, salud, ciclos, 24 h | Recursos › Energía | Primario (sólo portátiles) |
| Procesos y filtro | Procesos (desde CPU/Memoria/Más) | Detalle |
| Sesiones SSH, intentos fallidos, accesos, direcciones | Accesos › Ahora mismo / Registros de acceso | Primario |
| `PasswordAuthentication`, `PermitRootLogin` | Accesos › SSH | Primario |
| Reglas, silencio, historial, riesgo sin regla | Alertas | Primario |
| Vigilancia de caída y su estado | Alertas (estado en Ajustes y topbar) | Primario |
| Permisos del dispositivo y `exec.*` | Más › Permisos | Detalle |
| Direcciones, modo de conexión, latencia | Más › Conexión | Detalle |
| Hallazgos y comandos | Más › Diagnóstico | Detalle |
| Token cifrado, pin del certificado, dispositivos, zona peligrosa | Ajustes | Detalle |

## Qué se eliminó, movió o fusionó

| Antes | Ahora | Motivo |
|---|---|---|
| Inicio con CPU, memoria, energía, accesos, red y discos completos | Resumen con cuatro filas de estado y enlaces | Resumen debe triar, no contener el detalle. |
| Tarjeta de accesos en Inicio y pantalla Accesos escondida | Acceso top-level con tres sub-tabs | La apuesta 0.3.2 no puede estar detrás de una tarjeta. |
| Reglas y permisos sólo desde Ajustes | Alertas top-level; permisos en Más | Alertas es la apuesta de retención; permisos son gestión. |
| Direcciones duplicadas en Red y en la hoja Conexión | Sólo en Más › Conexión | Una sola fuente de verdad. |
| Hoja de Ajustes con todo dentro | Pantalla Ajustes + detalles | Una hoja larga no es navegación. |
| Barra inferior de 4 destinos sin back real | 5 destinos + pila + BackHandler + rail en wide | Android exige Atrás y adaptación estructural. |
| `Screens.kt`, `Manage.kt`, `Sheets.kt` | `ui/screens/*` por tema y `ui/Components.kt` | DX: un archivo por tema, no un monolito. |

## Sistema visual

Identidad existente, no reemplazada: fondo casi negro, acento menta, azul para
recepción, naranja para emisión, tarjetas redondeadas y mono sólo para datos.

- **Color**: `Palette` en `ui/Theme.kt`. `Muted #A6B1C2` y `Dim #7A8695` superan
  4.5:1 sobre `Bg` y `Surface`. El color de estado no va solo: siempre lleva
  texto o forma.
- **Semántica de estado**: menta = correcto, ámbar = atención, rojo = crítico,
  azul = información/red, naranja = calor o emisión.
- **Tipografía**: roles en `Type`: `screenTitle` 20/600, `metric` 28/500,
  `value` 20/600, `body` 13, `secondary` 12, `caption` 11, `micro` 10.5,
  `mono` 11.5. Números con `tnum`. No se inventan tamaños por pantalla.
- **Espaciado**: base 4; 12 entre tarjetas; 16 de margen lateral; 14–18 de
  padding de tarjeta; 10–14 dentro de grupos. Menos espacio dentro de un grupo
  que entre grupos.
- **Radios**: 18 para tarjetas, 12–14 para controles y filas, círculo para chips.
- **Componentes** (`ui/Components.kt`): `VitalsCard`, `CardHeader`, `Chip`,
  `Bar`, `StackBar`, `Chart`, `Ring`, `TopicTabs`, `Segmented`, `SearchField`,
  `TopBar`, `TabBar`, `NavRail`, `Banner`, `EmptyState`, `InfoRow`, `TextAction`.
- **Gráficas**: dominio adaptativo con piso razonable, rejilla discreta, punto
  final y resumen mín/med/máx. No se fija 0–100 si la serie vive en 15–30 %.
- **Movimiento**: transición de pantalla 180 ms (fade), barras/anillo 300 ms,
  toast 2,6 s. Sin coreografía de carga ni animación decorativa.

## Estados obligatorios

| Estado | Regla |
|---|---|
| Cargando | Decir qué operación real está en curso; no un spinner vacío. |
| Sin conexión | Tarjeta única con el error y “Reintentar”; nunca gráficas vacías. |
| Sin datos de un tema | Estado vacío que explique si es “no aplica”, “sin registros” o “no se pudo leer”. |
| Vacío positivo | Confirmar el bien: “Nadie está conectado”, “Ninguna regla disparada”. |
| Error de escritura | Banner con causa y recuperación; el toast sólo para éxito breve. |
| `reader` | Explicar el alcance y cómo reemparejar; no mostrar botones que fallarán. |
| Riesgo sin regla | Mostrarlo en Resumen y en Alertas, con acción para crear la regla. |

## Accesibilidad y adaptación

- Objetivo táctil mínimo 48×48 dp en navegación, acciones, pestañas y filas.
- `BackHandler` en todas las pantallas empujadas; nunca se secuestra Atrás.
- Insets de barra de estado y navegación en todas las pantallas; edge-to-edge.
- Wide ≥600 dp: `NavRail` lateral y sin barra inferior; compact: barra inferior.
- `font_scale` 1.3 probado en Overview, Recursos, Alertas y Diagnóstico.
- Semántica de `Role.Tab` + `selected` en pestañas; `contentDescription` en
  iconos y gráficas; el color nunca es la única señal.
- Textos en `sp`; nada de tamaños fijos en px.

## Reglas de copy

- Cada fila lleva sujeto y unidad: nunca «Ahora», «Estado», «Ninguno» o «Registros» a secas. Se escribe «Conectado ahora», «Estado del host», «Ningún intento fallido».
- Los términos relativos declaran su referencia y su periodo: «dirección nueva» significa primera vez vista en las últimas 24 h, aunque el rango visible sea de 7 días.
- Los totales de acceso usan la cobertura real (`covered_s`) del host: si sólo hay 2 días de registro, se dice «en 2 días» o «los registros cubren 2 días de 7», no «en 7 días».
- Un valor técnico se traduce y, si hace falta, el identificador crudo queda como detalle secundario: `exec.unit_restart` se acompaña de «Reiniciar un servicio del host».
- `operator` y `reader` sólo aparecen entre paréntesis junto a «control total» y «sólo lectura»; nunca como única etiqueta.
- La UI visible dice «dirección» donde la API dice `endpoint`, y «alcance» donde la API dice `scope`.
- Ayuda técnica: una línea corta por bloque. No se explican dos veces el mismo concepto ni se llena una tarjeta de párrafos.
- El color y el chip nunca son la única señal: el estado de un recurso, la severidad de un hallazgo y el riesgo de un permiso llevan texto.

## Glosario de interfaz

| Término | Uso | Evitar |
|---|---|---|
| host | La máquina monitorizada | equipo, servidor (salvo que sea el caso), máquina |
| regla | Condición que dispara un aviso | alerta (para la definición) |
| alerta / aviso | Estado o notificación que ve el usuario | notificación (para el concepto) |
| acceso | Entrada o sesión SSH | login, entrada |
| intento fallido | Intento de acceso rechazado | fallo, error de acceso |
| permiso | Capacidad del dispositivo | capability en la UI general |
| alcance | Permiso del token: control total o sólo lectura | scope en la UI |
| dirección | URL donde responde el agente | endpoint en la UI |
| vigilancia | Servicio que avisa si el host cae | watchdog en la UI |
| silenciar | Suspender avisos temporalmente | mutear, desactivar |
| crítico / alto / en orden | Estado de un recurso | “normal” como etiqueta redundante |
| carga media | Procesos que esperan CPU, media de 1, 5 y 15 min | load |
| swap | Memoria de respaldo en disco | — |
| salud / ciclos | Capacidad conservada y cargas completas de la batería | — |

## Decisiones abiertas

- **Multi-host**: la pila actual asume un host por sesión. Cuando llegue, Resumen
  será la lista de hosts y el detalle heredará esta estructura.
- **Mockup web**: quedó en la época de 4 pestañas. No es la fuente de verdad de
  la app; si se retoma, debe copiar este mapa, no al revés.
- **Ajustes**: el pin del certificado y el ID del dispositivo se muestran en
  claro porque no son secretos; el token sigue cifrado y sólo se muestra truncado.
- **Permisos**: `write.channels` aparece como «Todavía no disponible en esta
  versión» hasta que exista un canal configurado; el texto lo explica en la
  pantalla.
- **Dispositivos**: la lista de Ajustes muestra nombre, alcance, ID y última vez
  visto. El alcance se presenta como «sólo lectura» o «control total», nunca
  como el valor crudo `reader`/`operator`.
- **Dirección en loopback**: un endpoint en `127.0.0.1` se etiqueta «Este
  teléfono» en la píldora de conexión. Es un caso residual (el teléfono no
  alcanza el loopback del host), pero la etiqueta ya dice a qué se refiere.
- **Permisos `exec.*`**: la app solicita la aprobación, pero en 0.3.2 el agente
  todavía no ejecuta la operación. La pantalla y el diálogo lo dicen de forma
  explícita: conceder sólo autoriza, no ejecuta.

## Verificación

- `./gradlew assembleRelease` en verde con la firma de release.
- Capturas reales en Galaxy A54 (Android 16) para Resumen, los cinco tabs de
  Recursos, los tres de Accesos, Alertas, Más, Ajustes, Conexión, Procesos,
  Diagnóstico y Permisos.
- Probado `font_scale 1.3`, ancho compacto 343 dp, landscape con `NavRail` y
  vuelta atrás del sistema.
- Este documento y `app/README.md` se actualizan en el mismo cambio que la UI.
