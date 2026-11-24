#!/usr/bin/env bash
# Cut a release: bump versions, regenerate the changelog, commit and tag.
#
# Usage: ./scripts/release.sh <version>
# Example: ./scripts/release.sh 0.2.0
#
# This script never pushes. It prints the push commands at the end.
set -euo pipefail

usage() {
  echo "usage: $0 <version>    (for example: $0 0.2.0)" >&2
  exit 2
}

die() {
  echo "error: $*" >&2
  exit 1
}

[[ $# -eq 1 ]] || usage
version="${1#v}"

if [[ ! "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  die "invalid version '${1}', expected X.Y.Z (for example 0.2.0)"
fi

IFS=. read -r major minor patch <<< "${version}"
version_code=$(( major * 10000 + minor * 100 + patch ))
tag="v${version}"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

command -v git >/dev/null || die "git is not installed"
command -v python3 >/dev/null || die "python3 is required to rewrite version files"
command -v git-cliff >/dev/null || die \
  "git-cliff is not installed: https://git-cliff.org/docs/installation (e.g. 'cargo install git-cliff --locked')"

git rev-parse --git-dir >/dev/null 2>&1 || die "not a git repository"

git rev-parse --verify HEAD >/dev/null 2>&1 \
  || die "the repository has no commits yet; create the initial commit first"

branch="$(git symbolic-ref --quiet --short HEAD || true)"
[[ "${branch}" == "main" ]] || die "must run on 'main' (current: '${branch:-detached HEAD}')"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is not clean:" >&2
  git status --short >&2
  die "commit or stash your changes first"
fi

if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null; then
  die "tag ${tag} already exists"
fi

echo "==> Bumping to ${version} (Android versionCode ${version_code})"

python3 - "${version}" "${version_code}" <<'PY'
import re
import sys

version = sys.argv[1]
version_code = sys.argv[2]


def replace_once(path, pattern, replacement, what):
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    new_text, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        sys.exit(f"error: could not update {what} in {path}")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(new_text)


# Cargo.toml: only the [package] version, never a dependency version.
replace_once(
    "Cargo.toml",
    r'(?m)^(\[package\]\s*\n(?:.*\n)*?version = ")[^"]*(")',
    rf"\g<1>{version}\g<2>",
    "the package version",
)

# Cargo.lock records the root package version too; keep it in sync so that
# 'cargo build --locked' in CI does not fail.
replace_once(
    "Cargo.lock",
    r'(?m)^(name = "vitals"\nversion = ")[^"]*(")',
    rf"\g<1>{version}\g<2>",
    "the vitals entry",
)


LITERAL = re.compile(r'"[^"]*"|\b\d+(?:\.\d+)*\b')


def replace_last_literal(expr, prop, value):
    """Replace the last literal in expr, ignoring the property name itself.

    Keeping the literal type matters: a quoted default may be converted with
    toInt(), so 'getOrElse("1")' must become 'getOrElse("200")', while a bare
    numeric default must stay numeric.
    """
    lits = [m for m in LITERAL.finditer(expr) if m.group(0) != f'"{prop}"']
    if not lits:
        return None
    last = lits[-1]
    replacement = f'"{value}"' if last.group(0).startswith('"') else value
    return expr[: last.start()] + replacement + expr[last.end() :]


def set_gradle_field(field, value, prop):
    """Update versionName/versionCode, whatever shape the build script uses.

    Supported shapes:
      versionName = "0.1.0"
      versionName = (findProperty("vitalsVersionName") as String?) ?: "0.1.0"
      val vitalsVersionName = providers.gradleProperty("vitalsVersionName")
          .getOrElse("0.1.0")
      versionName = vitalsVersionName
    """
    path = "app/build.gradle.kts"
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    changed = False

    # 1. Property declaration: refresh its default literal.
    declaration = re.compile(
        rf'(?m)^.*(?:gradleProperty|findProperty)\("{re.escape(prop)}"\).*$'
    )
    match = declaration.search(text)
    if match:
        new = replace_last_literal(match.group(0), prop, value)
        if new is not None:
            text = text[: match.start()] + new + text[match.end() :]
            changed = True

    # 2. defaultConfig assignment. If it just references a val, update the val.
    assignment = re.compile(rf"(?m)^([ \t]*{field}[ \t]*=[ \t]*)(.+)$")
    match = assignment.search(text)
    if not match:
        sys.exit(f"error: '{field}' not found in {path}")

    expr = match.group(2)
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", expr):
        val = re.compile(
            rf"(?m)^[ \t]*val[ \t]+{re.escape(expr)}[ \t]*=[ \t]*(.+)$"
        ).search(text)
        if val:
            new = replace_last_literal(val.group(1), prop, value)
            if new is not None and new != val.group(1):
                text = text[: val.start(1)] + new + text[val.end(1) :]
                changed = True
    else:
        new = replace_last_literal(expr, prop, value)
        if new is not None:
            text = text[: match.start(2)] + new + text[match.end(2) :]
            changed = True

    if not changed:
        sys.exit(f"error: could not update {field} in {path}")

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


set_gradle_field("versionName", version, "vitalsVersionName")
set_gradle_field("versionCode", version_code, "vitalsVersionCode")
PY

echo "==> Regenerating CHANGELOG.md with git-cliff"
git-cliff --tag "${tag}" --output CHANGELOG.md

# Replace the TU_USUARIO/vitals placeholder with the real repository when an
# origin remote is configured, so the compare links work on GitHub.
origin_url="$(git remote get-url origin 2>/dev/null || true)"
if [[ "${origin_url}" =~ github\.com[:/]([^/]+)/([^/]+) ]]; then
  origin_owner="${BASH_REMATCH[1]}"
  origin_repo="${BASH_REMATCH[2]%.git}"
  sed "s|TU_USUARIO/vitals|${origin_owner}/${origin_repo}|g" CHANGELOG.md > CHANGELOG.md.tmp
  mv CHANGELOG.md.tmp CHANGELOG.md
else
  echo "warning: no GitHub 'origin' remote; CHANGELOG.md keeps the" >&2
  echo "         TU_USUARIO/vitals placeholder in its links" >&2
fi

echo "==> Committing and tagging"
git add Cargo.toml Cargo.lock app/build.gradle.kts CHANGELOG.md
git commit -m "chore(release): ${tag}"
git tag -a "${tag}" -m "vitals ${version}"

echo
echo "Release ${tag} is ready locally. Nothing was pushed."
echo "Push it with:"
echo
echo "    git push origin main"
echo "    git push origin ${tag}"
echo
echo "The tag push triggers .github/workflows/release.yml, which builds the"
echo "server tarballs and the Android APK and publishes the GitHub Release."
