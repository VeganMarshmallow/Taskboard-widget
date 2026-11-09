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
  "shell pidof system_server")
    # FAKE_PIDS: the pid each call prints, in order (the last repeats; "-" prints nothing; "1,2" prints "1 2").
    n=$(( $(cat "$dir/pids" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$dir/pids"
    read -ra seq <<< "\${FAKE_PIDS:-100}"
    i=$(( n <= \${#seq[@]} ? n - 1 : \${#seq[@]} - 1 ))
    [ "\${seq[$i]}" = - ] || printf '%s\\r\\n' "\${seq[$i]//,/ }";;
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
  assert.match(yml, /if \[ "\$\(emulator_verdict failure app\/build\/outputs\/androidTest-results\/connected\/debug 1\)" = retry \]; then\n/);
  assert.match(yml, /id: emulator_retry\n\s+if: steps\.retry\.outputs\.retry == 'true'\n\s+continue-on-error: true\n\s+uses: reactivecircus\/android-emulator-runner@v2\n\s+with: \*emulator-tests/);
  // With continue-on-error on both tries, the verdict step must still fail the job.
  assert.match(yml, /- name: Instrumented test verdict\n\s+if: steps\.emulator\.outcome == 'failure'\n/);
  assert.match(yml, /\[ "\$RETRIED" = true \] \|\| exit 1\n/);
  assert.match(yml, /emulator_verdict "\$RETRY_OUTCOME" app\/build\/outputs\/androidTest-results\/connected\/debug 2\)" = pass/);
  assert.match(yml, /::warning title=Emulator retried::emulator failed to start; retried once and passed/);
});

test('build.yml: the retry cold-boots, the first try loads the snapshot', () => {
  const yml = fs.readFileSync(path.join(__dirname, '..', 'workflows', 'build.yml'), 'utf8');
  assert.match(yml, /emulator-options: -no-snapshot-save .* \$\{\{ steps\.retry\.outputs\.cold_boot \}\}\n/);
  assert.match(yml, /echo "cold_boot=-no-snapshot-load" >> "\$GITHUB_OUTPUT"/);
});

// #61: the snapshot is saved only after system_server stayed up (same pid) for the idle window.
const pidCalls = (dir) => Number(fs.readFileSync(path.join(dir, 'pids'), 'utf8'));

test('settle_system: settles at once when system_server keeps its pid', () => {
  const dir = sandbox();
  const r = run('settle_system 10 0 0', dir);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /System settled: system_server 100 stayed up for 0s/);
  assert.strictEqual(pidCalls(dir), 2);
  assert.doesNotMatch(r.stdout, /::warning/);
});

test('settle_system: waits again when system_server restarts during the idle window', () => {
  const dir = sandbox();
  const r = run('settle_system 10 0 0', dir, { FAKE_PIDS: '100 200 200' });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /::warning title=Emulator::system_server restarted while settling; waiting again/);
  assert.match(r.stdout, /System settled: system_server 200/);
  assert.strictEqual(pidCalls(dir), 4);
});

test('settle_system: no system_server pid is not settled', () => {
  const dir = sandbox();
  const r = run('settle_system 10 0 0', dir, { FAKE_PIDS: '- - 300 300' });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /System settled: system_server 300/);
});

test('settle_system: fails with an annotation when system_server keeps restarting', () => {
  const dir = sandbox();
  const r = run('settle_system 1 0.3 0', dir, { FAKE_PIDS: '1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16' });
  assert.strictEqual(r.status, 1);
  assert.match(r.stdout, /::error title=Emulator::system did not settle within 1s/);
});

test('settle_system: fails when the system never comes up, without sleeping the idle window', () => {
  const dir = sandbox();
  const start = Date.now();
  const r = run('settle_system 1 30 0.2', dir, { FAKE_UP_AFTER: '0' });
  assert.strictEqual(r.status, 1);
  assert.match(r.stdout, /::error title=Emulator::system not ready/);
  assert.ok(Date.now() - start < 10000, 'it must not wait the idle window');
});

test('settle_system: system_server gone after the idle window is not settled', () => {
  const dir = sandbox();
  const r = run('settle_system 10 0 0', dir, { FAKE_PIDS: '100 - 100 100' });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.strictEqual((r.stdout.match(/restarted while settling/g) || []).length, 1);
  assert.strictEqual(pidCalls(dir), 4);
});

test('settle_system: the system going down during the idle window is not settled', () => {
  const dir = sandbox();
  // pm answers, then fails once (the check after the first idle window), then answers again.
  fs.writeFileSync(path.join(dir, 'adb'), FAKE_ADB.replace('[ -n "${FAKE_PM_DEAD:-}" ]',
    'm=$(( $(cat "$dir/pm" 2>/dev/null || echo 0) + 1 )); echo "$m" > "$dir/pm"; [ "$m" = 2 ]'), { mode: 0o755 });
  const r = run('settle_system 10 0 0', dir);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.strictEqual((r.stdout.match(/restarted while settling/g) || []).length, 1);
  assert.match(r.stdout, /System settled/);
});

test('snapshot-settle.sh is executable and avd-setup creates the snapshot through it', () => {
  const script = path.join(__dirname, 'snapshot-settle.sh');
  assert.ok(fs.statSync(script).mode & 0o111, 'snapshot-settle.sh must be executable');
  // settle_system is the last command, so its failure is the script's exit status.
  assert.match(fs.readFileSync(script, 'utf8'), /^settle_system 300 30\n?$/m);
  assert.match(fs.readFileSync(script, 'utf8').trimEnd(), /\nsettle_system 300 30$/);
  const action = fs.readFileSync(path.join(__dirname, '..', 'actions', 'avd-setup', 'action.yml'), 'utf8');
  const create = action.split('- name: Create AVD snapshot')[1].split('\n    - name:')[0];
  assert.match(create, /^\s+script: \.github\/scripts\/snapshot-settle\.sh$/m);
  // The snapshot is saved on exit, so the create step must not disable saving.
  assert.doesNotMatch(create, /-no-snapshot-save/);
  // An unsettled system fails the create step, and then the snapshot must not be saved.
  const save = action.split('- name: Save AVD snapshot')[1];
  assert.doesNotMatch(create, /continue-on-error/);
  assert.doesNotMatch(save, /always\(\)|failure\(\)|continue-on-error/);
  for (const step of [create, save]) {
    assert.match(step, /if: steps\.avd\.outputs\.cache-hit != 'true' && github\.ref == 'refs\/heads\/main'/);
  }
});

test('settle_system: several system_server pids that stay the same are settled', () => {
  const dir = sandbox();
  const r = run('settle_system 10 0 0', dir, { FAKE_PIDS: '100,101' });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /System settled: system_server 100 101 stayed up/);
});

test('settle_system: one of several system_server pids changing is not settled', () => {
  const dir = sandbox();
  const r = run('settle_system 10 0 0', dir, { FAKE_PIDS: '100,101 100,102 100,102' });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.strictEqual((r.stdout.match(/restarted while settling/g) || []).length, 1);
  assert.match(r.stdout, /System settled: system_server 100 102/);
});

test('settle_system: fails when pidof never finds system_server', () => {
  const dir = sandbox();
  const r = run('settle_system 1 0.2 0', dir, { FAKE_PIDS: '-' });
  assert.strictEqual(r.status, 1);
  assert.match(r.stdout, /::error title=Emulator::system did not settle within 1s/);
  assert.doesNotMatch(r.stdout, /System settled/);
});

test('settle_system: a timeout of 0 still makes one settle attempt', () => {
  let dir = sandbox();
  let r = run('settle_system 0 0 0', dir);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /System settled: system_server 100/);
  dir = sandbox();
  r = run('settle_system 0 0 0', dir, { FAKE_PIDS: '1 2 3' });
  assert.strictEqual(r.status, 1);
  assert.match(r.stdout, /did not settle within 0s/);
  assert.strictEqual(pidCalls(dir), 2);
});

test('snapshot-settle.sh settles through emulator-lib.sh from any working directory', () => {
  const dir = sandbox();
  fs.writeFileSync(path.join(dir, 'sleep'), '#!/usr/bin/env bash\nexit 0\n', { mode: 0o755 });
  const r = spawnSync('bash', [path.join(__dirname, 'snapshot-settle.sh')], {
    cwd: dir, encoding: 'utf8', env: { ...process.env, PATH: `${dir}:${process.env.PATH}` },
  });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /System settled: system_server 100 stayed up for 30s/);
});

// #80: the retry decision.
function verdict(outcome, results, attempt) {
  const dir = sandbox();
  fs.mkdirSync(path.join(dir, 'res'));
  if (results) fs.writeFileSync(path.join(dir, 'res', 'TEST-one.xml'), '');
  const r = run(`emulator_verdict ${outcome} res ${attempt}`, dir);
  assert.strictEqual(r.status, 0, r.stderr);
  return r.stdout.trim();
}

test('emulator_verdict: a passing attempt passes, first try or retry', () => {
  assert.strictEqual(verdict('success', true, 1), 'pass');
  assert.strictEqual(verdict('success', true, 2), 'pass');
});

test('emulator_verdict: no test result after a failed first try retries', () => {
  assert.strictEqual(verdict('failure', false, 1), 'retry');
});

test('emulator_verdict: a failure with test results is never retried', () => {
  assert.strictEqual(verdict('failure', true, 1), 'fail');
  assert.strictEqual(verdict('failure', true, 2), 'fail');
});

test('emulator_verdict: a second failure fails, with or without results (one retry at most)', () => {
  assert.strictEqual(verdict('failure', false, 2), 'fail');
  assert.strictEqual(verdict('failure', false, 3), 'fail');
});

test('emulator_verdict: a retry that was skipped or cancelled fails', () => {
  assert.strictEqual(verdict('skipped', false, 2), 'fail');
  assert.strictEqual(verdict('cancelled', false, 2), 'fail');
  assert.strictEqual(verdict("''", false, 2), 'fail');
});

test('emulator_verdict: a missing results dir counts as no results', () => {
  const dir = sandbox();
  assert.strictEqual(run('emulator_verdict failure nope 1', dir).stdout.trim(), 'retry');
});

test('emulator_verdict: an unexpected attempt value never retries, and prints no shell error', () => {
  for (const attempt of ['0', '-1', 'abc', '1.5', '99']) {
    const dir = sandbox();
    const r = run(`emulator_verdict failure nope ${attempt}`, dir);
    assert.strictEqual(r.stdout.trim(), 'fail', attempt);
    assert.strictEqual(r.stderr, '', attempt);
  }
  assert.strictEqual(run('emulator_verdict failure nope', sandbox()).stdout.trim(), 'retry');
});

test('emulator_verdict: an empty dir or only non-JUnit files count as no results; a zero-byte TEST-*.xml counts', () => {
  const dir = sandbox();
  fs.mkdirSync(path.join(dir, 'res dir'));
  assert.strictEqual(run('emulator_verdict failure "res dir" 1', dir).stdout.trim(), 'retry');
  fs.writeFileSync(path.join(dir, 'res dir', 'output-metrics.xml'), '<x/>');
  fs.writeFileSync(path.join(dir, 'res dir', 'test-result.pb'), '');
  assert.strictEqual(run('emulator_verdict failure "res dir" 1', dir).stdout.trim(), 'retry');
  fs.writeFileSync(path.join(dir, 'res dir', 'TEST-emulator-5554.xml'), '');
  assert.strictEqual(run('emulator_verdict failure "res dir" 1', dir).stdout.trim(), 'fail');
  assert.strictEqual(run('emulator_verdict success "res dir" 1', dir).stdout.trim(), 'pass');
});

test('build.yml: emulator-recent keeps its own steps (no retry, no shared anchor), and failures after the tests still fail', () => {
  const yml = fs.readFileSync(path.join(__dirname, '..', 'workflows', 'build.yml'), 'utf8');
  const recent = yml.split(/\n  emulator-recent:\n/)[1].split(/\n  [\w-]+:\n/)[0];
  assert.doesNotMatch(recent, /\*emulator-tests|steps\.retry|emulator_verdict/);
  const job = yml.split(/\n  emulator:\n/)[1].split(/\n  emulator-recent:\n/)[0];
  // Only the two emulator tries may swallow their failure.
  assert.strictEqual((job.match(/continue-on-error: true/g) || []).length, 2);
  assert.match(job, /- name: Annotate instrumented test failure\n\s+if: failure\(\) && steps\.emulator\.outcome == 'failure'\n/);
});

test('restart_adb kills and restarts the adb server, and never fails', () => {
  const dir = sandbox();
  fs.writeFileSync(path.join(dir, 'adb'), `#!/usr/bin/env bash\necho "$*" >> "${dir}/adb.log"; exit 1\n`, { mode: 0o755 });
  const r = run('restart_adb', dir);
  assert.strictEqual(r.status, 0, r.stderr);
  assert.strictEqual(fs.readFileSync(path.join(dir, 'adb.log'), 'utf8'), 'kill-server\nstart-server\n');
});

// Fakes for stop_emulator: pgrep sees qemu until FAKE_QEMU_FILE is gone; adb emu kill removes it
// unless FAKE_QEMU_STUCK; pkill logs and removes it.
function stopSandbox({ stuck = false } = {}) {
  const dir = sandbox();
  const qemu = path.join(dir, 'qemu-running');
  fs.writeFileSync(qemu, '');
  fs.writeFileSync(path.join(dir, 'adb'),
    `#!/usr/bin/env bash\n[ "$*" = "emu kill" ] && [ -z "${stuck ? '1' : ''}" ] && rm -f "${qemu}"; exit 0\n`, { mode: 0o755 });
  fs.writeFileSync(path.join(dir, 'pgrep'), `#!/usr/bin/env bash\n[ -e "${qemu}" ]\n`, { mode: 0o755 });
  fs.writeFileSync(path.join(dir, 'pkill'), `#!/usr/bin/env bash\necho "$*" >> "${dir}/pkill.log"; rm -f "${qemu}"\n`, { mode: 0o755 });
  const avd = path.join(dir, 'avd', 'test.avd');
  fs.mkdirSync(path.join(avd, 'multiinstance.lock'), { recursive: true });
  fs.writeFileSync(path.join(avd, 'hardware-qemu.ini.lock'), '');
  fs.writeFileSync(path.join(avd, 'config.ini'), '');
  return { dir, avd, env: { ANDROID_AVD_HOME: path.join(dir, 'avd') } };
}

test('stop_emulator: a clean quit needs no kill, and the lock files go but the AVD stays', () => {
  const { dir, avd, env } = stopSandbox();
  const r = run('stop_emulator 5', dir, env);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.ok(!fs.existsSync(path.join(dir, 'pkill.log')));
  assert.deepStrictEqual(fs.readdirSync(avd), ['config.ini']);
});

test('stop_emulator: kills qemu when it does not quit in time', () => {
  const { dir, avd, env } = stopSandbox({ stuck: true });
  const r = run('stop_emulator 1', dir, env);
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
  assert.match(fs.readFileSync(path.join(dir, 'pkill.log'), 'utf8'), /-9 -f qemu-system/);
  assert.deepStrictEqual(fs.readdirSync(avd), ['config.ini']);
});

test('stop_emulator: fine with no emulator and no AVD dir at all', () => {
  const dir = sandbox();
  fs.writeFileSync(path.join(dir, 'pgrep'), '#!/usr/bin/env bash\nexit 1\n', { mode: 0o755 });
  const r = run('stop_emulator 1', dir, { ANDROID_AVD_HOME: path.join(dir, 'none') });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
});

test('build.yml: the retry stops the failed emulator and restarts adb first, and step ids are unique per job', () => {
  const yml = fs.readFileSync(path.join(__dirname, '..', 'workflows', 'build.yml'), 'utf8');
  assert.match(yml, /stop_emulator 30\n\s+restart_adb\n\s+echo "retry=true"/);
  for (const job of yml.split(/\n  [\w-]+:\n/)) {
    const ids = [...job.matchAll(/^\s+id: (\S+)$/gm)].map((m) => m[1]);
    assert.deepStrictEqual(ids, [...new Set(ids)], `duplicate step id in: ${ids}`);
  }
});
