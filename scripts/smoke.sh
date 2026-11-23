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

start_agent() {
  "$BIN" --config "$STATE/config.toml" --state-dir "$STATE" serve >>"$STATE/serve.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 40); do
    if curl -fsS --cacert "$CACERT" "$BASE/healthz" >/dev/null 2>&1; then return 0; fi
    if ! kill -0 "$PID" 2>/dev/null; then
      echo "server exited during startup:" >&2
      cat "$STATE/serve.log" >&2
      exit 1
    fi
    sleep 0.25
  done
  echo "server did not answer healthz" >&2
  cat "$STATE/serve.log" >&2
  exit 1
}

restart_agent() {
  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
  start_agent
}

start_agent

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

# The collector may still be on its first tick right after startup.
for _ in $(seq 1 40); do
  METRICS=$(curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/metrics" 2>/dev/null || true)
  echo "$METRICS" | grep -q '"cpu"' && break
  sleep 0.25
done
echo "$METRICS" | grep -q '"cpu"' || fail "metrics"
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

# --- 0.2: scopes, command, audit and per-device grants --------------------
ctl() {
python3 - "$STATE/control.sock" "$1" <<'PY'
import json, socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(10)
s.connect(sys.argv[1])
s.sendall((sys.argv[2] + "\n").encode())
buf = b""
while not buf.endswith(b"\n"):
    chunk = s.recv(65536)
    if not chunk:
        break
    buf += chunk
print(buf.decode().strip())
PY
}

post_command() { # token, body -> status (body in $STATE/command.json)
  curl -s -o "$STATE/command.json" -w '%{http_code}' --cacert "$CACERT" \
    -H "Authorization: Bearer $1" -H 'content-type: application/json' \
    -d "$2" "$BASE/api/v1/command"
}

OP_PAIR_JSON=$(ctl '{"cmd":"pair","name":"operator","scope":"operator"}')
echo "$OP_PAIR_JSON" | grep -q '"scope":"operator"' || fail "operator pairing code"
OP_CODE=$(echo "$OP_PAIR_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"]["code"])')
OP_PAIR=$(curl -fsS --cacert "$CACERT" -H 'content-type: application/json' \
  -d "{\"code\":\"$OP_CODE\",\"device_name\":\"operator\"}" "$BASE/api/v1/pair")
echo "$OP_PAIR" | grep -q '"scope":"operator"' || fail "operator token"
OP_TOKEN=$(echo "$OP_PAIR" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
OP_ID=$(echo "$OP_PAIR" | python3 -c 'import json,sys; print(json.load(sys.stdin)["device_id"])')
OPAUTH=(-H "Authorization: Bearer $OP_TOKEN")
pass "operator token carries the operator scope"

code=$(post_command "$TOKEN" '{"v":1,"id":"r1","op":"caps.request","args":{"cap":"exec.unit_restart","units":["jellyfin.service"]}}')
[[ "$code" == "403" ]] || fail "reader command returned $code, expected 403"
pass "reader token is rejected by command"

code=$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/audit")
[[ "$code" == "403" ]] || fail "reader audit returned $code, expected 403"
pass "reader token cannot read the audit log"

code=$(post_command "$OP_TOKEN" '{"v":1,"id":"s1","op":"exec.run_shell","args":{}}')
[[ "$code" == "400" ]] || fail "unknown op returned $code, expected 400"
grep -q '"code":"schema"' "$STATE/command.json" || fail "unknown op error code"
pass "unknown operation rejected by the closed schema"

code=$(post_command "$OP_TOKEN" '{"v":1,"id":"s2","op":"caps.request","args":{},"shell":"rm -rf /"}')
[[ "$code" == "400" ]] || fail "unknown envelope field returned $code, expected 400"
pass "unknown envelope fields rejected"

BIG=$(python3 -c 'import json; print(json.dumps({"v":1,"id":"big","op":"caps.request","args":{},"pad":"x"*5000}))')
code=$(post_command "$OP_TOKEN" "$BIG")
[[ "$code" == "413" ]] || fail "oversized command returned $code, expected 413"
pass "commands over 4 KB rejected"

code=$(post_command "$OP_TOKEN" '{"v":1,"id":"x1","op":"exec.unit_restart","args":{"unit":"jellyfin.service"}}')
[[ "$code" == "403" ]] || fail "exec without manifest returned $code, expected 403"
grep -q '"code":"capability_not_granted"' "$STATE/command.json" || fail "missing capability code"
pass "operator without a manifest gets capability_not_granted"

REQ_A='{"v":1,"id":"req-a1","op":"caps.request","args":{"cap":"exec.unit_restart","units":["jellyfin.service"]}}'
curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" -H 'content-type: application/json' \
  -d "$REQ_A" "$BASE/api/v1/command" > "$STATE/req-a.json" || fail "caps.request A"
REQ_A_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["result"]["request_id"])' "$STATE/req-a.json")
REQ_A_CODE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["result"]["code"])' "$STATE/req-a.json")
[[ ${#REQ_A_CODE} -eq 6 ]] || fail "approval code is not 6 chars"
pass "caps.request returns a pending request and a 6-character code"

curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/caps" > "$STATE/caps-pending.json" || fail "caps pending"
python3 - "$STATE/caps-pending.json" "$REQ_A_CODE" <<'PY' || fail "caps does not show the pending request"
import json, sys
data = json.load(open(sys.argv[1]))
exec_cap = next(c for c in data["capabilities"] if c["id"] == "exec.unit_restart")
assert exec_cap["state"] == "pending", exec_cap
assert exec_cap["request"]["code"] == sys.argv[2], exec_cap
PY
pass "pending state and its code are visible to the app"

curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" -H 'content-type: application/json' \
  -d "$REQ_A" "$BASE/api/v1/command" > "$STATE/req-a-replay.json" || fail "caps.request replay"
python3 - "$STATE/req-a.json" "$STATE/req-a-replay.json" <<'PY' || fail "idempotent replay changed the result"
import json, sys
a = json.load(open(sys.argv[1]))["result"]
b = json.load(open(sys.argv[2]))["result"]
assert a["request_id"] == b["request_id"] and a["code"] == b["code"], (a, b)
PY
pass "command id is idempotent for 24 h"

"$BIN" --state-dir "$STATE" caps pending | grep -q "exec.unit_restart" || fail "caps pending"
"$BIN" --state-dir "$STATE" caps approve 1 --yes >/dev/null || fail "caps approve by list"
grep -q "exec.unit_restart" "$STATE/capabilities.toml" || fail "manifest not written"
pass "grant approved by list is written to the manifest"

curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/caps" > "$STATE/caps-granted.json" || fail "caps after approval"
python3 - "$STATE/caps-granted.json" <<'PY' || fail "caps does not show the approved grant"
import json, sys
data = json.load(open(sys.argv[1]))
exec_cap = next(c for c in data["capabilities"] if c["id"] == "exec.unit_restart")
assert exec_cap["state"] == "granted" and exec_cap["granted_by"] == "manifest", exec_cap
assert exec_cap["params"]["units"] == ["jellyfin.service"], exec_cap
PY
pass "the app sees the grant and its narrowed parameters"

code=$(post_command "$OP_TOKEN" '{"v":1,"id":"x2","op":"exec.unit_restart","args":{"unit":"jellyfin.service"}}')
[[ "$code" == "501" ]] || fail "authorized exec returned $code, expected 501 not_implemented"
grep -q '"code":"not_implemented"' "$STATE/command.json" || fail "not_implemented code"
pass "granted exec is authorized but not executed in 0.2"

code=$(post_command "$OP_TOKEN" '{"v":1,"id":"x3","op":"exec.unit_restart","args":{"unit":"sshd.service"}}')
[[ "$code" == "403" ]] || fail "unit outside allow-list returned $code, expected 403"
grep -q '"code":"denied"' "$STATE/command.json" || fail "allow-list denial code"
pass "units outside the allow-list are denied"

# Device B: same scope, separate grants, approved by code.
B_PAIR_JSON=$(ctl '{"cmd":"pair","name":"operator-b","scope":"operator"}')
B_CODE=$(echo "$B_PAIR_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"]["code"])')
B_PAIR=$(curl -fsS --cacert "$CACERT" -H 'content-type: application/json' \
  -d "{\"code\":\"$B_CODE\",\"device_name\":\"operator-b\"}" "$BASE/api/v1/pair")
B_TOKEN=$(echo "$B_PAIR" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
B_ID=$(echo "$B_PAIR" | python3 -c 'import json,sys; print(json.load(sys.stdin)["device_id"])')

REQ_B='{"v":1,"id":"req-b1","op":"caps.request","args":{"cap":"exec.unit_restart","units":["jellyfin.service"]}}'
curl -fsS --cacert "$CACERT" -H "Authorization: Bearer $B_TOKEN" -H 'content-type: application/json' \
  -d "$REQ_B" "$BASE/api/v1/command" > "$STATE/req-b.json" || fail "caps.request B"
REQ_B_CODE=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["result"]["code"])' "$STATE/req-b.json")

code=$(post_command "$B_TOKEN" '{"v":1,"id":"x4","op":"exec.unit_restart","args":{"unit":"jellyfin.service"}}')
[[ "$code" == "403" ]] || fail "B before approval returned $code, expected 403"
pass "a grant for A does not leak to B"

"$BIN" --state-dir "$STATE" caps approve "$REQ_B_CODE" --yes >/dev/null || fail "caps approve by code"
code=$(post_command "$B_TOKEN" '{"v":1,"id":"x5","op":"exec.unit_restart","args":{"unit":"jellyfin.service"}}')
[[ "$code" == "501" ]] || fail "B after code approval returned $code, expected 501"
pass "approval by code produces the same grant as approval by list"

"$BIN" --state-dir "$STATE" caps revoke "$OP_ID" exec.unit_restart >/dev/null || fail "caps revoke A"
code=$(post_command "$OP_TOKEN" '{"v":1,"id":"x6","op":"exec.unit_restart","args":{"unit":"jellyfin.service"}}')
[[ "$code" == "403" ]] || fail "A after revoke returned $code, expected 403"
code=$(post_command "$B_TOKEN" '{"v":1,"id":"x7","op":"exec.unit_restart","args":{"unit":"jellyfin.service"}}')
[[ "$code" == "501" ]] || fail "B after revoking A returned $code, expected 501"
pass "revoking A leaves B's grant intact"

curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/caps" > "$STATE/caps-revoked.json" || fail "caps after revoke"
python3 - "$STATE/caps-revoked.json" <<'PY' || fail "caps still shows the revoked grant"
import json, sys
data = json.load(open(sys.argv[1]))
exec_cap = next(c for c in data["capabilities"] if c["id"] == "exec.unit_restart")
assert exec_cap["state"] == "not_granted", exec_cap
PY
pass "the app sees the revocation immediately"

curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/audit" > "$STATE/audit.json" || fail "operator audit"
python3 - "$STATE/audit.json" <<'PY' || fail "audit contents"
import json, sys
entries = json.load(open(sys.argv[1]))["entries"]
assert len(entries) >= 6, entries
outcomes = {e["outcome"] for e in entries}
codes = {e["code"] for e in entries}
assert "rejected" in outcomes and "accepted" in outcomes, outcomes
assert "capability_not_granted" in codes and "not_implemented" in codes, codes
PY
pass "operator reads the full audit log, rejections included"

# Diagnostics and suggestions are reads for any token.
curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/diagnostics" > "$STATE/diagnostics.json" || fail "diagnostics"
curl -fsS --cacert "$CACERT" "${AUTH[@]}" "$BASE/api/v1/suggest" > "$STATE/suggest.json" || fail "suggest"
python3 - "$STATE/diagnostics.json" "$STATE/suggest.json" <<'PY' || fail "diagnostics payload"
import json, sys
findings = json.load(open(sys.argv[1]))["findings"]
suggestions = json.load(open(sys.argv[2]))["suggestions"]
assert isinstance(findings, list) and isinstance(suggestions, list)
for finding in findings:
    assert {"id", "severity", "title", "detail", "evidence"} <= finding.keys(), finding
for suggestion in suggestions:
    assert suggestion["command"] and suggestion["finding"], suggestion
PY
pass "reader gets structured diagnostics and exact commands"

curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/caps" > "$STATE/caps.json" || fail "caps list"
python3 - "$STATE/caps.json" "$OP_ID" <<'PY' || fail "caps payload"
import json, sys
data = json.load(open(sys.argv[1]))
assert data["device_id"] == sys.argv[2], data
assert data["scope"] == "operator"
by_id = {c["id"]: c for c in data["capabilities"]}
assert by_id["read.audit"]["state"] == "granted"
assert by_id["exec.unit_restart"]["state"] == "not_granted"
PY
pass "caps shows per-device grants and risks"

# --- 0.2: alert rules, hysteresis, silence and persistence ----------------
ALERTS_SET='{"v":1,"id":"alerts-a1","op":"alerts.set","args":{"rules":[{"id":"disk-root","kind":"disk","mount":"/","percent":85,"days":7},{"id":"mem","kind":"memory","percent":90},{"id":"cpu-temp","kind":"temperature","celsius":95}]}}'
code=$(post_command "$OP_TOKEN" "$ALERTS_SET")
[[ "$code" == "200" ]] || fail "alerts.set returned $code, expected 200"
curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/alerts" > "$STATE/alerts.json" || fail "alerts list"
python3 - "$STATE/alerts.json" <<'PY' || fail "alerts.set did not persist three rules"
import json, sys
data = json.load(open(sys.argv[1]))
assert len(data["rules"]) == 3, data["rules"]
assert {r["kind"] for r in data["rules"]} == {"disk", "memory", "temperature"}
PY
pass "alerts.set stores the three rule kinds"

code=$(post_command "$OP_TOKEN" '{"v":1,"id":"alerts-bad","op":"alerts.set","args":{"rules":[{"id":"x","kind":"memory","percent":90,"celsius":80}]}}')
[[ "$code" == "400" ]] || fail "rule with foreign field returned $code, expected 400"
grep -q '"code":"schema"' "$STATE/command.json" || fail "rule validation error code"
pass "alert rules reject fields from another kind"

restart_agent
curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/alerts" > "$STATE/alerts.json" || fail "alerts after restart"
python3 - "$STATE/alerts.json" <<'PY' || fail "rules did not survive the agent restart"
import json, sys
data = json.load(open(sys.argv[1]))
assert len(data["rules"]) == 3, data["rules"]
PY
pass "alert rules survive an agent restart (and vitals-update)"

# A threshold that is always crossed: exactly one fired event, even across
# repeated evaluations and a no-op alerts.set with the same definition.
SET_MEM='{"v":1,"id":"alerts-a2","op":"alerts.set","args":{"rules":[{"id":"mem","kind":"memory","percent":0.1}]}}'
code=$(post_command "$OP_TOKEN" "$SET_MEM")
[[ "$code" == "200" ]] || fail "alerts.set mem returned $code"

fired_count() {
  curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" "$BASE/api/v1/alerts" 2>/dev/null \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(sum(1 for e in d["events"] if e["rule_id"]=="mem" and e["state"]=="fired"))'
}

FIRED=0
for _ in $(seq 1 30); do
  FIRED=$(fired_count || echo 0)
  [[ "$FIRED" == "1" ]] && break
  sleep 0.5
done
[[ "$FIRED" == "1" ]] || fail "expected one fired event, got $FIRED"
pass "an always-bad rule fires exactly once"

sleep 6
FIRED_AGAIN=$(fired_count || echo 0)
[[ "$FIRED_AGAIN" == "1" ]] || fail "duplicate fired event after re-evaluation: $FIRED_AGAIN"
code=$(post_command "$OP_TOKEN" "$SET_MEM")
[[ "$code" == "200" ]] || fail "idempotent alerts.set returned $code"
sleep 6
FIRED_AGAIN=$(fired_count || echo 0)
[[ "$FIRED_AGAIN" == "1" ]] || fail "alerts.set with the same definition re-alerted: $FIRED_AGAIN"
pass "hysteresis and unchanged definitions do not duplicate alerts"

curl -fsS --cacert "$CACERT" "${OPAUTH[@]}" -H 'content-type: application/json' \
  -d '{"v":1,"id":"alerts-quiet","op":"alerts.silence","args":{"minutes":480}}' \
  "$BASE/api/v1/command" > "$STATE/silence.json" || fail "alerts.silence"
python3 - "$STATE/silence.json" <<'PY' || fail "silence did not reset the firing state"
import json, sys
result = json.load(open(sys.argv[1]))["result"]
assert result["silenced"] == 1, result
assert all(r["silence_until"] > 0 and not r["firing"] for r in result["rules"]), result["rules"]
PY
sleep 6
FIRED_AGAIN=$(fired_count || echo 0)
[[ "$FIRED_AGAIN" == "1" ]] || fail "silence did not suppress new alerts: $FIRED_AGAIN"
pass "silence suppresses alerts and records the silenced episode"

code=$(post_command "$OP_TOKEN" '{"v":1,"id":"alerts-del","op":"alerts.delete","args":{"id":"mem"}}')
[[ "$code" == "200" ]] || fail "alerts.delete returned $code"
python3 - "$STATE/command.json" <<'PY' || fail "alerts.delete did not remove the rule"
import json, sys
result = json.load(open(sys.argv[1]))["result"]
assert result["deleted"] is True, result
PY
pass "alerts.delete removes a rule"

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
