// commitlint configuration for vitals.
//
// The rules are declared inline instead of extending
// `@commitlint/config-conventional` so that CI can run the linter with a single
// `npx @commitlint/cli` invocation: this repository is Rust, it has no
// package.json and no node_modules to resolve an extended config from.
//
// Keep the type list in sync with the parsers in `cliff.toml`: a type that the
// changelog groups must be accepted here.

export default {
  // Merge commits are ignored by commitlint's built-in ignore list.
  defaultIgnores: true,
  parserPreset: {
    name: 'vitals',
    parserOpts: {
      headerPattern: /^(\w+)(?:\(([\w$.\-* ]*)\))?!?: (.*)$/,
      headerCorrespondence: ['type', 'scope', 'subject'],
    },
  },
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'feat', // Añadido
        'fix', // Corregido
        'perf', // Rendimiento
        'refactor', // Cambios internos
        'style', // Cambios internos
        'docs', // Documentación
        'build', // Build y CI
        'ci', // Build y CI
        'test', // Pruebas
        'chore', // Otros, unless it is chore(deps) or chore(release)
        'revert', // Otros
      ],
    ],
    'type-empty': [2, 'never'],
    'type-case': [2, 'always', 'lower-case'],
    'scope-case': [2, 'always', 'lower-case'],
    'subject-empty': [2, 'never'],
    // Off on purpose: commits mix Spanish and English, and the default rule
    // rejects upper-case words inside a subject.
    'subject-case': [0],
    'subject-full-stop': [2, 'never', '.'],
    'header-max-length': [2, 'always', 100],
    'body-leading-blank': [2, 'always'],
    'footer-leading-blank': [2, 'always'],
    // Agents and CI tools write long single-line bodies; the subject still
    // carries the conventional format.
    'body-max-line-length': [0, 'always'],
    'footer-max-line-length': [0, 'always'],
  },
};
