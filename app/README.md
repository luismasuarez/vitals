# vitals · app Android

Cliente nativo del agente `vitals`. Es la misma interfaz que el mockup web
(`mockup/web`) pero hablando la API real: pinning del certificado, failover de
endpoints y SSE.

## Requisitos

- JDK 17 (`JAVA_HOME` apuntando a él).
- Android SDK con `platforms;android-35` y `build-tools;35.0.0`.
- Gradle 8.11.1 (o el wrapper, si está presente).
- `local.properties` con `sdk.dir=/ruta/al/Android/sdk`.

> Nota del equipo: `dl.google.com` es inestable desde esta red, así que
> `settings.gradle.kts` incluye el mirror de Aliyun como segundo repositorio.
> El SDK también trae un init script que añade el mismo mirror; por eso
> `repositoriesMode` es `PREFER_SETTINGS`.

## Compilar e instalar

```bash
cd app
./gradlew assembleDebug            # o: gradle assembleDebug
adb install -r build/outputs/apk/debug/vitals-app-debug.apk
```

Para una versión concreta (lo usa el workflow de release):

```bash
gradle assembleRelease \
  -PvitalsVersionName=0.2.0 \
  -PvitalsVersionCode=200 \
  -PvitalsKeystore=/ruta/release.jks \
  -PvitalsKeystorePassword=... \
  -PvitalsKeyAlias=... \
  -PvitalsKeyPassword=...
```

Sin `-PvitalsKeystore` el build release queda sin firmar; el pipeline usa
`apksigner` en ese caso.

## Emparejar

1. En el host: `sudo vitals pair --name "Galaxy A54"`.
2. En la app: **Escanear QR**.
3. Si la cámara falla, hay dos alternativas: pegar el payload `vitals1:…` o
   lanzar la app con el intent (útil para pruebas automatizadas):

```bash
adb shell am start -n dev.vitals.app/.MainActivity -e pair "vitals1:..."
```

## Cómo está armada

| Archivo | Responsabilidad |
|---|---|
| `core/Models.kt` | Tipos de la API y parseo con `org.json` (sin dependencias extra) |
| `core/VitalsApi.kt` | `X509TrustManager` con pin truncado, failover y EventSource |
| `core/Session.kt` | Persistencia de la sesión en SharedPreferences |
| `AppViewModel.kt` | Estado Compose, SSE, polling de respaldo, pairing |
| `ui/Onboarding.kt` | Cámara con CameraX + ML Kit, pasos de emparejamiento |
| `ui/Screens.kt` | Inicio, CPU, Red, Procesos |
| `ui/Components.kt` | Tarjetas, anillo, gráficos en Canvas, iconos, tab bar |

Decisiones que conviene no deshacer:

- **El pin es la única autoridad de confianza.** No se valida CA ni hostname:
  un cambio de IP por DHCP no rompe nada y un atacante en la LAN no puede
  interponerse sin la clave del host.
- **SSE con `fetch`/EventSource de OkHttp y polling de respaldo** cada 2,5 s si
  el stream cae. El indicador del topbar muestra `SSE` o `polling`.
- **Failover en cada request**: si el endpoint activo no responde, se prueba el
  siguiente del QR y se recuerda el que funcionó.

## Limitaciones conocidas

- El token se guarda en SharedPreferences en claro. En producción debe ir al
  Keystore (`EncryptedSharedPreferences` con clave no exportable).
- Sin servicio en primer plano: si Android mata el proceso, el stream se
  reabre al volver a la app.
- Sin histórico local: cada rango se pide al host (que sí lo conserva).
- La app asume un host por sesión. Varios hosts implican varias sesiones, no
  implementado todavía.
