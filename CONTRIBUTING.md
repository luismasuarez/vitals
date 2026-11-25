# Contribuir a vitals

Dos cosas fijas: cómo se escriben los commits y cómo sale una versión. La
versión y el `CHANGELOG.md` son consecuencia de los commits: no se editan a
mano.

## Convención de commits

Formato: `tipo(alcance): asunto`, por ejemplo `fix(app): stop decoding null strings`.
El asunto va en imperativo y en inglés; el cuerpo puede ser en inglés o español.

| Tipo | Sección del changelog |
|---|---|
| `feat` | Añadido |
| `fix` | Corregido |
| `perf` | Rendimiento |
| `refactor`, `style` | Cambios internos |
| `docs` | Documentación |
| `build`, `ci` | Build y CI |
| `test` | Pruebas |
| `revert` | Otros |
| `chore` | Oculto |

`chore` queda oculto por completo: release-please filtra por tipo, no por
alcance, así que no puede excluir `chore(deps)` y `chore(release)` dejando el
resto visible. Los commits de merge también se ignoran.

La lista de tipos aceptados vive en `commitlint.config.mjs` y las secciones en
`release-please-config.json`. Un tipo que no esté en las dos queda rechazado o
invisible, así que se actualizan juntos.

### Cambios incompatibles

Marca el tipo con `!` (`feat!:`, `fix!:`) y añade un pie que explique qué rompe
y cómo migrar:

```
feat(api)!: require a capability token on the command endpoint

BREAKING CHANGE: `POST /command` now returns 403 without the `control`
capability. Pair the device again to grant it.
```

release-please los recoge en una sección propia de las notas del release.

## Validación de commits

CI ejecuta commitlint sobre los commits de cada PR y sobre el rango empujado a
`main`. Para comprobarlo antes de empujar, sin instalar nada:

```bash
echo 'feat(app): add a battery chart' | npx --yes @commitlint/cli@19
```

No hay hook local: CI es la fuente de verdad y la única puerta obligatoria.

## Proceso de release (automático)

1. Los commits convencionales se fusionan en `main`.
2. El workflow `Release` ejecuta release-please y abre o actualiza el PR
   `chore(release): X.Y.Z`. Ese PR toca `CHANGELOG.md`, `Cargo.toml`,
   `Cargo.lock` y el `versionName` de `app/build.gradle.kts`.
3. Al fusionar ese PR, release-please crea el tag `vX.Y.Z` y la GitHub Release
   con el changelog como notas.
4. En la misma ejecución, los jobs de build compilan los binarios musl
   (x86_64 y aarch64) y el APK, suben los artefactos y `SHA256SUMS`, y amplían
   las notas con la tabla de descargas y las instrucciones de instalación.

No hay pasos manuales de versión ni de tag. El despliegue sí es manual: el
servidor y el móvil se actualizan a mano desde los artefactos publicados.

### Requisito en GitHub

release-please abre un PR con el `GITHUB_TOKEN`, así que el repositorio necesita
`Settings → Actions → General → Workflow permissions → Allow GitHub Actions to
create and approve pull requests`. En este repositorio ya está activado; si se
clona o se mueve, actívalo antes del primer push a `main` o el job fallará con
un error de permisos.

### Forzar una versión

Si necesitas un número concreto (por ejemplo el primer release tras migrar),
empuja un commit vacío con el pie `Release-As`:

```bash
git commit --allow-empty -m "chore: release 0.3.2" -m "Release-As: 0.3.2"
```

## Reglas que no se negocian

- `CHANGELOG.md`, la versión de `Cargo.toml`/`Cargo.lock` y el `versionName` de
  Android los escribe release-please. No se editan a mano, ni para corregir una
  errata: se corrige el commit y se regenera.
- El `versionCode` de Android (`major*10000 + minor*100 + patch`) se deriva del
  `versionName` en `app/build.gradle.kts`. No es un valor que se mantenga aparte.
- Si una versión incluye una migración de datos o una variable de entorno nueva,
  añade una sección `### Migraciones` al cuerpo del PR de release antes de
  fusionarlo. No se infiere del diff.
- Las versiones anteriores a la migración se generaron con git-cliff; a partir
  de `0.3.2` manda release-please.
