# Handoff — vitals

Estado: servidor, app Android, mockup web y pipeline de release completos. La
app está instalada y verificada contra el agente real en un Galaxy A54
(`SM_A546E`).

## Qué está hecho

| Área | Archivos |
|---|---|
| Colector (CPU, RAM, red, discos, temps, procesos) | `src/collect/` |
| API HTTP + SSE | `src/api/` |
| TLS propio con pin truncado | `src/tls.rs` |
| Pairing y tokens por dispositivo | `src/auth.rs`, `src/control.rs` |
| Histórico SQLite (raw 10 s + rollup 1 min) | `src/store.rs` |
| Selección de interfaces a escuchar | `src/net.rs` |
| CLI (`serve`, `pair`, `devices`, `tls`) | `src/main.rs`, `src/cli.rs` |
| Unidad systemd endurecida | `deploy/vitals.service` |
| App Android (Compose) | `app/src/main/java/dev/vitals/app/` |
| Mockup web con proxy y pin | `mockup/` |
| CI + releases + changelog | `.github/workflows/`, `cliff.toml`, `scripts/release.sh` |

## Decisiones que no conviene reabrir sin motivo

1. **Certificado propio con pin, no Tailscale Serve.** Mismo puerto y mismo pin
   en LAN y tailnet, sin depender de Tailscale en el móvil.
2. **Bind por dirección, nunca `0.0.0.0`.** Es lo que mantiene el puerto
   invisible en un VPS.
3. **Código de pairing de un solo uso en el QR**, no el token.
4. **Socket unix para administración.** `DynamicUser` impide que el CLI escriba
   el estado; el socket resuelve el problema sin abrir otro puerto.
5. **SSE + polling de respaldo** en la app, con failover por request.
6. **El mockup web va por proxy**: el navegador no puede fijar el certificado ni
   usar la cámara sobre HTTP, así que `mockup/serve.py` verifica el pin y
   reenvía. Requiere `tailscale serve` para contexto seguro.

## Verificación que existe hoy

```bash
cargo test                 # 13 tests
cargo clippy --all-targets # sin warnings
bash scripts/smoke.sh      # 13 checks end-to-end
cd app && ./gradlew assembleDebug
```

Probado a mano en el móvil: emparejamiento por intent, datos en vivo por SSE,
cambio de pestañas, hoja de ajustes, rebuild de release.

## Pendiente

- Token en el Keystore de Android (hoy SharedPreferences).
- Revocación desde la app: la API es de sólo lectura, se hace con
  `vitals devices revoke`.
- Primer release real: commit inicial, `origin`, secrets de firma, `git push` y
  `./scripts/release.sh 0.1.0` (requiere `git-cliff`).
- Multi-host en la app.

## Notas del entorno

- `static.rust-lang.org` bloqueado: Rust se instaló vía mirror de Tsinghua.
- `dl.google.com` inestable desde esta red: el SDK trae un init script que
  añade el mirror de Aliyun, y `app/settings.gradle.kts` lo declara también.
- Gradle 8.11.1 en `~/.local/share/gradle-8.11.1`; wrapper generado en
  `app/gradlew`.
- El APK debug se llama `vitals-app-debug.apk` (el rootProject es `vitals-app`).

## Cómo retomar

```bash
cd ~/tools/vitals
export PATH="$HOME/.cargo/bin:$PATH"
cargo test && bash scripts/smoke.sh
cd app && ./gradlew assembleDebug
adb install -r build/outputs/apk/debug/vitals-app-debug.apk
```
