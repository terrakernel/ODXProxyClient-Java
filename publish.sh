#!/usr/bin/env bash
#
# Build, sign, and upload the current version to the Sonatype Central Portal
# staging repository. After this script succeeds, finish the release by
# clicking "Publish" at https://central.sonatype.com → Publishing.
#
# Credentials live OUTSIDE the repo at: ~/.config/odxproxy/publish.env
# That file is sourced by this script. It is never committed.
#
# Required keys in publish.env:
#   MAVEN_CENTRAL_USERNAME=<central-portal-token-user>
#   MAVEN_CENTRAL_PASSWORD=<central-portal-token-password>
#   GPG_KEY_ID=<short-or-long-key-id, e.g. B628E5AE>
#   GPG_KEY_PASSWORD=<passphrase>
#
# The script exports the key itself fresh from your local GPG keyring on
# every run — no ASCII-armored private key is stored on disk.

set -euo pipefail

CONFIG_FILE="${HOME}/.config/odxproxy/publish.env"

if [[ ! -f "${CONFIG_FILE}" ]]; then
  cat >&2 <<EOF
error: ${CONFIG_FILE} not found.

Create it with mode 600:

  mkdir -p ~/.config/odxproxy
  touch ~/.config/odxproxy/publish.env
  chmod 600 ~/.config/odxproxy/publish.env

Then add:
  MAVEN_CENTRAL_USERNAME=<central-portal-token-user>
  MAVEN_CENTRAL_PASSWORD=<central-portal-token-password>
  GPG_KEY_ID=<short-or-long-gpg-key-id>
  GPG_KEY_PASSWORD=<gpg-passphrase>
EOF
  exit 1
fi

# Refuse to source a world-readable secrets file.
perms=$(stat -c '%a' "${CONFIG_FILE}" 2>/dev/null || stat -f '%A' "${CONFIG_FILE}")
if [[ "${perms}" != "600" && "${perms}" != "400" ]]; then
  echo "error: ${CONFIG_FILE} has permissions ${perms}; expected 600 or 400." >&2
  echo "       run: chmod 600 ${CONFIG_FILE}" >&2
  exit 1
fi

# Parse KEY=VALUE lines manually so values with spaces / shell metacharacters
# work without requiring the user to quote them. (Sourcing the file directly
# would fail under `set -u` if a password contains an unquoted space.)
while IFS='=' read -r key value; do
  # Skip blank lines and comments.
  [[ -z "${key// }" || "${key:0:1}" == "#" ]] && continue
  # Trim surrounding whitespace from the key; strip optional matching quotes
  # from the value but otherwise preserve it byte-for-byte.
  key="${key// }"
  if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]] \
  || [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf -v "${key}" '%s' "${value}"
  export "${key?}"
done < "${CONFIG_FILE}"

for var in MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD GPG_KEY_ID GPG_KEY_PASSWORD; do
  if [[ -z "${!var:-}" ]]; then
    echo "error: ${var} is empty or unset in ${CONFIG_FILE}" >&2
    exit 1
  fi
done

# Export the secret key from GPG into memory only — never to disk.
echo ">> exporting GPG key ${GPG_KEY_ID} from local keyring..."
GPG_KEY_ARMORED=$(gpg --export-secret-keys --armor "${GPG_KEY_ID}")
if [[ -z "${GPG_KEY_ARMORED}" ]]; then
  echo "error: gpg --export-secret-keys for ${GPG_KEY_ID} returned empty." >&2
  echo "       check: gpg --list-secret-keys ${GPG_KEY_ID}" >&2
  exit 1
fi

VERSION=$(grep -E '^version = "' build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
echo ">> publishing version ${VERSION}"

read -r -p ">> proceed with upload to Central Portal staging? [y/N] " ans
if [[ "${ans}" != "y" && "${ans}" != "Y" ]]; then
  echo "aborted."
  exit 0
fi

export ORG_GRADLE_PROJECT_mavenCentralUsername="${MAVEN_CENTRAL_USERNAME}"
export ORG_GRADLE_PROJECT_mavenCentralPassword="${MAVEN_CENTRAL_PASSWORD}"
export ORG_GRADLE_PROJECT_signingInMemoryKey="${GPG_KEY_ARMORED}"
export ORG_GRADLE_PROJECT_signingInMemoryKeyId="${GPG_KEY_ID}"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="${GPG_KEY_PASSWORD}"

./gradlew clean build publishToMavenCentral --no-configuration-cache

cat <<EOF

✅ Uploaded ${VERSION} to Sonatype Central Portal staging.

   Next step: open https://central.sonatype.com → Publishing,
   verify the deployment, and click "Publish" to release it.

   Once released, the artifact is immutable.
EOF
