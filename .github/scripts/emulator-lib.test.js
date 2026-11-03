// Tests for .github/scripts/emulator-lib.sh (#58), with a fake adb. Run: node --test .github/scripts/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const lib = path.join(__dirname, 'emulator-lib.sh');

// The fake adb counts calls; the system is "up" from the FAKE_UP_AFTER-th getprop on (0: never).
const FAKE_ADB = `#!/usr/bin/env bash
dir="$(dirname "$0")"
case "$*" in
  "shell getprop sys.boot_completed")
    n=$(( $(cat "$dir/polls" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$dir/polls"
    up="\${FAKE_UP_AFTER:-1}"
    if [ "$up" -gt 0 ] && [ "$n" -ge "$up" ]; then printf '1\\r\\n'; else echo "adb: device offline" >&2; exit 1; fi;;
  "shell pm path android")
    [ -n "\${FAKE_PM_DEAD:-}" ] && { echo "Error: android.os.DeadSystemException" >&2; exit 255; }
    echo "package:/system/framework/framework-res.apk";;
esac
`;

function sandbox() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'emulator-lib-'));
  fs.writeFileSync(path.join(dir, 'adb'), FAKE_ADB, { mode: 0o755 });
  return dir;
}

function run(snippet, dir, env = {}) {
  return spawnSync('bash', ['-c', `. "${lib}"; ${snippet}`], {
    cwd: dir, encoding: 'utf8',
    env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, ...env },
  });
}

const polls = (dir) => Number(fs.readFileSync(path.join(dir, 'polls'), 'utf8'));

test('has_test_results: false for a missing or empty dir, or one without TEST-*.xml', () => {
  const dir = sandbox();
  assert.strictEqual(run('has_test_results nope', dir).status, 1);
  fs.mkdirSync(path.join(dir, 'res'));
  assert.strictEqual(run('has_test_results res', dir).status, 1);
  fs.writeFileSync(path.join(dir, 'res', 'other.xml'), '');
  assert.strictEqual(run('has_test_results res', dir).status, 1);
});

test('has_test_results: true with one or many results, also under a path with spaces', () => {
  const dir = sandbox();
  const res = path.join(dir, 'a b');
  fs.mkdirSync(res);
  fs.writeFileSync(path.join(res, 'TEST-one.xml'), '');
  assert.strictEqual(run('has_test_results "a b"', dir).status, 0);
  fs.writeFileSync(path.join(res, 'TEST-two.xml'), '');
  assert.strictEqual(run('has_test_results "a b"', dir).status, 0);
});

test('wait_for_system returns at once when the system is up', () => {
  const dir = sandbox();
  const r = run('wait_for_system 10 0', dir);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.strictEqual(polls(dir), 1);
});

test('wait_for_system keeps polling while the device is offline', () => {
  const dir = sandbox();
  const r = run('wait_for_system 10 0', dir, { FAKE_UP_AFTER: '3' });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.strictEqual(polls(dir), 3);
});

test('wait_for_system fails with an annotation when boot never completes', () => {
  const dir = sandbox();
  const r = run('wait_for_system 1 0.2', dir, { FAKE_UP_AFTER: '0' });
  assert.strictEqual(r.status, 1);
  assert.match(r.stdout, /::error title=Emulator::system not ready after 1s/);
});

test('wait_for_system fails when boot_completed is 1 but system_server is dead', () => {
  const dir = sandbox();
  const r = run('wait_for_system 1 0.2', dir, { FAKE_PM_DEAD: '1' });
  assert.strictEqual(r.status, 1);
  assert.ok(polls(dir) > 1, 'it should poll again before giving up');
});

test('build.yml retries the emulator step only when no test result exists', () => {
  const yml = fs.readFileSync(path.join(__dirname, '..', 'workflows', 'build.yml'), 'utf8');
  assert.match(yml, /id: emulator\n\s+continue-on-error: true\n/);
  assert.match(yml, /if has_test_results app\/build\/outputs\/androidTest-results\/connected\/debug; then\n\s+echo "retry=false"/);
  assert.match(yml, /if: steps\.retry\.outputs\.retry == 'true'\n\s+uses: reactivecircus\/android-emulator-runner@v2\n\s+with: \*emulator-tests/);
  // With continue-on-error, a real failure must still fail the job.
  assert.match(yml, /if: steps\.emulator\.outcome == 'failure' && steps\.retry\.outputs\.retry != 'true'\n\s+run: exit 1/);
});
