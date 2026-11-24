#!/usr/bin/env bash
# End-to-end check: TLS, pin, pairing, auth, metrics, history, SSE, revoke.
# Runs against a throwaway state dir and an unprivileged port.
set -euo pipefail

BIN="${BIN:-./target/debug/vitals}"
PORT="${PORT:-$(( (RANDOM % 20000) + 20000 ))}"
STATE="$(mktemp -d /tmp/opencode/vitals-smoke.XXXXXX)"
BASE="https://127.0.0.1:$PORT"
CACERT="$STATE/tls-cert.pem"

cleanup() {
  [[ -n "${PID:-}" ]] && kill "$PID" 2>/dev/null || true
  wait "${PID:-}" 2>/dev/null || true
}
trap cleanup EXIT

printf 'port = %s\n' "$PORT" > "$STATE/config.toml"

"$BIN" --config "$STATE/config.toml" --state-dir "$STATE" serve >"$STATE/serve.log" 2>&1 &
PID=$!

for _ in $(seq 1 40); do
  if curl -fsS --cacert "$CACERT" "$BASE/healthz" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "server exited during startup:" >&2
    cat "$STATE/serve.log" >&2
    exit 1
  fi
  sleep 0.25
done

fail() { echo "FAIL: $*" >&2; tail -20 "$STATE/serve.log" >&2; exit 1; }
pass() { echo "ok   $*"; }

curl -fsS --cacert "$CACERT" "$BASE/healthz" | grep -q '"ok":true' || fail "healthz"
pass "healthz over pinned TLS"

code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CACERT" "$BASE/api/v1/metrics")
[[ "$code" == "401" ]] || fail "metrics without token returned $code, expected 401"
pass "unauthenticated request rejected"

# Pair through the unix socket, exactly like `vitals pair` does.
PAIR_JSON=$(python3 - "$STATE/control.sock" <<'PY'
import json, socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(10)
s.connect(sys.argv[1])
s.sendall(b'{"cmd":"pair","name":"smoke","ttl":300}\n')
buf = b""
while not buf.endswith(b"\n"):
    chunk = s.recv(4096)
    if not chunk:
        break
    buf += chunk
print(buf.decode())
PY
)
echo "$PAIR_JSON" | grep -q '"ok":true' || fail "pair command: $PAIR_JSON"
CODE=$(echo "$PAIR_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"]["code"])')
PIN=$(echo "$PAIR_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"]["pin"])')
pass "pairing code issued (pin ${PIN:0:8}...)"

TOKEN=$(curl -fsS --cacert "$CACERT" -H 'content-type: application/json' \
  -d "{\"code\":\"$CODE\",\"device_name\":\"smoke\"}" "$BASE/api/v1/pair" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
[[ -n "$TOKEN" ]] || fail "pair exchange"
pass "code exchanged for a device token"

AUTH=(-H "Authorization: Bearer $TOKEN")

curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/metrics" | grep -q '"cpu"' || fail "metrics"
pass "metrics snapshot"

curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/host" | grep -q '"hostname"' || fail "host"
pass "static host info"

curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/history?range=1h" | grep -q '"points"' || fail "history"
pass "history query"

code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/history?range=forever")
[[ "$code" == "400" ]] || fail "bad range returned $code, expected 400"
pass "invalid range rejected"

curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/processes?sort=mem&limit=3" | grep -q '"processes"' || fail "processes"
pass "process list"

curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/info" | grep -q '"pin"' || fail "info"
pass "device info"

SSE=$(curl -sN --max-time 3 --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/stream" || true)
echo "$SSE" | grep -q 'event: snapshot' || fail "sse stream produced no snapshot"
pass "SSE snapshot received"

# A second use of the same code must fail: codes are single use.
code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CACERT" -H 'content-type: application/json' \
  -d "{\"code\":\"$CODE\"}" "$BASE/api/v1/pair")
[[ "$code" == "401" ]] || fail "reused code returned $code, expected 401"
pass "pairing code cannot be reused"

DEVICE_ID=$(echo "$PAIR_JSON" | python3 -c 'import json,sys; print("")' ) # placeholder
DEVICE_ID=$(curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/info" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; print(d[0]["id"])')

python3 - "$STATE/control.sock" "$DEVICE_ID" <<'PY'
import json, socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(10)
s.connect(sys.argv[1])
s.sendall((json.dumps({"cmd": "revoke", "id": sys.argv[2]}) + "\n").encode())
print(s.recv(4096).decode().strip())
PY

code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/metrics")
[[ "$code" == "401" ]] || fail "revoked token returned $code, expected 401"
pass "revoked token stops working"

echo
echo "all checks passed (state: $STATE)"
