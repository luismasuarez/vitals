# Probar en el móvil

Estado: app de producción `0.1.0` instalada en el Galaxy A54 (release, firmada,
sólo `arm64-v8a`). Falta levantar el agente y emparejar.

## 1. Instalar y arrancar el agente

```bash
curl -fsSL https://raw.githubusercontent.com/luismasuarez/vitals/main/scripts/install.sh | bash
```

Eso descarga el binario correcto para la arquitectura, verifica el checksum,
lo instala en `~/.local/bin`, registra el servicio de usuario y lo arranca.
Con `sudo` delante hace lo mismo a nivel de sistema (`/usr/local/bin` +
`/etc/vitals` + unidad systemd).

Comprobar estado:

```bash
systemctl --user status vitals      # instalación de usuario
journalctl --user -u vitals -n 20
```

## 2. Emparejar con el QR

```bash
vitals pair --name "mi-telefono"              # scope reader
vitals pair --operator --name "mi-telefono"   # scope operator
```

Imprime un QR en la terminal y, debajo, la misma carga en texto (`vitals1:…`).
El código es de un solo uso y caduca en 5 minutos. Usa `--operator` si vas a
crear reglas o leer la auditoría desde ese móvil; para sólo consultar métricas,
`reader` es suficiente.

En el móvil:

1. Abre **vitals**.
2. Toca **Escanear QR** y concede el permiso de cámara.
3. Apunta al QR de la terminal.
4. La app muestra host, endpoints, pin y caducidad → **Emparejar**.

Si el QR no se lee (fuente, zoom o terminal con fondo claro):

- Toca **Pegar el código a mano** y pega la línea `vitals1:…` que aparece bajo
  el QR.
- Genera otro código: cada uno se consume al usarse o al caducar.

## 3. Verificar

- **Inicio**: nombre del host, anillo de CPU, memoria, tráfico, discos.
- **Píldora de la cabecera**: `LAN` con punto verde = stream SSE activo;
  ámbar = polling de respaldo.
- **Red → Conexión**: endpoints con el activo marcado; tocar otro fuerza el
  cambio.
- **Ajustes** (engranaje): pin, token enmascarado, dispositivos emparejados.

Fuera de casa, activa Tailscale en el móvil: el failover pasa al endpoint
`100.x` sin cambiar nada en la app.

### Vigilancia (host caído)

1. En la app: **Ajustes → Vigilancia** y activa el interruptor. Concede el
   permiso de notificaciones si Android 13+ lo pide.
2. Comprueba que hay una notificación permanente `vitals vigilando · <host>` y
   que el topbar muestra `vigilando`.
3. En el host, para el agente:

   ```bash
   systemctl --user stop vitals
   ```

4. En menos de 45 s debe llegar **una** notificación `"<host> no responde"`.
   El log del servicio lo confirma:

   ```bash
   adb logcat -s VitalsWatchdog
   # W/VitalsWatchdog: host down after 30s without events
   ```

5. Arranca el agente (`systemctl --user start vitals`) y espera el primer
   `snapshot`: debe llegar **una** notificación de recuperación y el aviso de
   caída debe desaparecer.

   ```bash
   # I/VitalsWatchdog: host recovered after 41s
   ```

6. Si Android mata el servicio, WorkManager lo revive en ≤15 min. Se puede
   forzar la prueba con:

   ```bash
   adb shell am force-stop dev.vitals.app
   adb shell cmd jobscheduler run -f dev.vitals.app 0   # o esperar al job periódico
   ```

   En Android 12+ el arranque desde segundo plano puede estar bloqueado; abrir
   la app siempre lo relanza.

7. Batería (8 h con pantalla apagada): resetear estadísticas, dejar la
   vigilancia activa y medir. Objetivo <5 %.

   ```bash
   adb shell dumpsys batterystats --reset
   # 8 h después:
   adb shell dumpsys batterystats --charged dev.vitals.app | head -40
   ```

## 4. Operación

```bash
sudo vitals devices list
sudo vitals devices revoke <device_id>
sudo vitals tls show
```

### Capacidades por dispositivo

Un token `operator` puede pedir una capability de alto riesgo desde la app, pero
no la obtiene hasta que alguien en el host la aprueba:

```bash
sudo vitals caps pending           # qué pide cada dispositivo, con riesgo y parámetros
sudo vitals caps approve 1         # por número de la lista
sudo vitals caps approve 7KQ2M9    # o por el código de 6 caracteres de la app
sudo vitals caps list              # concesiones vigentes y su manifiesto
sudo vitals caps revoke <device_id> exec.unit_restart
```

El manifiesto vive en `/etc/vitals/capabilities.toml` (instalación de sistema) o
en `~/.local/state/vitals/capabilities.toml` (instalación de usuario). Es `0644`
y sólo lo escribe `vitals caps`. Aprobar en un dispositivo no concede nada a
otro. En 0.2 `exec.*` se autoriza pero no se ejecuta: responde
`not_implemented`.

Comprobar la auditoría desde el móvil `operator` o con `curl`:

```bash
curl --cacert ~/.local/state/vitals/tls-cert.pem \
  -H "Authorization: Bearer <token>" https://127.0.0.1:8443/api/v1/audit
```

### Alertas

Crear reglas desde un token `operator` (o desde la app, cuando esté la pantalla):

```bash
curl --cacert ~/.local/state/vitals/tls-cert.pem \
  -H "Authorization: Bearer <token>" -H 'content-type: application/json' \
  -d '{"v":1,"id":"t1","op":"alerts.set","args":{"rules":[
        {"id":"disk-root","kind":"disk","mount":"/","percent":85,"days":7},
        {"id":"mem","kind":"memory","percent":90},
        {"id":"cpu-temp","kind":"temperature","celsius":95}]}}' \
  https://127.0.0.1:8443/api/v1/command
```

```bash
# reglas, eventos y predicción de días
curl --cacert ~/.local/state/vitals/tls-cert.pem \
  -H "Authorization: Bearer <token>" https://127.0.0.1:8443/api/v1/alerts

# silenciar 8 h
curl ... -d '{"v":1,"id":"t2","op":"alerts.silence","args":{"minutes":480}}' ...
```

Para forzar un disparo, pon `percent: 0.1` en la regla de memoria: debe
aparecer un único evento `fired`, no uno por muestra. Reinicia el agente
(`systemctl --user restart vitals`) y comprueba que las reglas siguen ahí; el
historial y las reglas viven en SQLite, así que `vitals-update` tampoco las toca.

### Reglas desde la app

Con un token `operator`:

1. **Ajustes → Reglas de alerta**.
2. Toca **Añadir** en una de las tres plantillas (disco, memoria, temperatura).
3. La regla aparece en **Activas** con su estado y predicción de días.

Desde ahí puedes silenciar 1/8/24 h y ver el historial. Con un token `reader`
la pantalla explica que hace falta `vitals pair --operator` y no muestra el
formulario.

### Permisos desde la app

1. **Ajustes → Permisos por dispositivo**.
2. En una capability `exec.*`, toca **Solicitar**, revisa el riesgo y completa
   la unidad (o el tamaño máximo).
3. La app muestra el estado `pendiente` y el código de 6 caracteres. Apruébalo
   en el host con `sudo vitals caps approve <código>`.
4. Vuelve a abrir/actualizar la pantalla: debe pasar a `concedido` con los
   parámetros aprobados. Revocar en el host debe volver a `sin conceder`.

### Diagnóstico

**Ajustes → Diagnóstico** lista los hallazgos del host y, debajo de cada uno, el
comando exacto que lo arreglaría. Es texto: ni la app ni el agente lo ejecutan.
Con `curl`:

```bash
curl --cacert ~/.local/state/vitals/tls-cert.pem \
  -H "Authorization: Bearer <token>" https://127.0.0.1:8443/api/v1/diagnostics
curl --cacert ~/.local/state/vitals/tls-cert.pem \
  -H "Authorization: Bearer <token>" https://127.0.0.1:8443/api/v1/suggest
```

Rotar el certificado (obliga a re-emparejar):

```bash
sudo systemctl stop vitals
sudo rm /var/lib/vitals/tls-cert.pem /var/lib/vitals/tls-key.pem
sudo systemctl start vitals
```

## 5. Actualizar

Agente (un comando, sin recordar URLs):

```bash
vitals-update              # o: sudo vitals-update
vitals-update --check      # sólo compara versiones
```

App: descarga `vitals-<version>-android.apk` de la página de releases e
instálala encima (misma clave de firma, los datos se conservan):

```bash
adb install -r vitals-0.1.0-android.apk
```

La APK es sólo `arm64-v8a`. El keystore de firma vive en
`~/keystores/vitals-release.jks` (copia de seguridad fuera del repo) y sus
cuatro secrets ya están configurados en GitHub.

Tras instalar 0.2 encima de 0.1.x, el token debe migrar solo:

1. La app **no** debe pedir reemparejar.
2. **Ajustes → Almacenamiento** debe decir `Android Keystore`.
3. Con un build debug, el XML de preferencias no debe contener el token:

   ```bash
   adb shell run-as dev.vitals.app cat shared_prefs/vitals.session.xml
   # debe aparecer token_enc="v1:..." y ningún token="<device_id>.<secret>"
   ```

En un build release no debuggable esta inspección no es posible; la prueba es
que la sesión sobrevive al reinicio y Ajustes muestra Keystore. Si se borra la
clave del Keystore, la app pedirá reemparejar (comportamiento esperado).

## 6. Si algo falla

| Síntoma | Revisar |
|---|---|
| La app no conecta en LAN | `journalctl -u vitals`, puerto 8443, firewall del host |
| Conecta pero sin datos | misma red Wi-Fi, IP en el QR correcta |
| `pin mismatch` | el certificado se rotó: re-empareja |
| 401 en Ajustes | token revocado en el host: re-empareja |
| QR no se lee | usa el pegado manual |

El repositorio es privado. Para descargas públicas:
`gh repo edit --visibility public`.
