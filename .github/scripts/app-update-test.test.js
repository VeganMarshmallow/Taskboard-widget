// Tests for .github/scripts/app-update-test.sh (#42), with a fake adb. Run: node --test .github/scripts/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const script = path.join(__dirname, 'app-update-test.sh');
const receiverKt = path.join(__dirname, '..', '..',
  'app/src/main/kotlin/io/github/veganmarshmallow/taskboard/widget/AppUpdatedReceiver.kt');

// The fake adb appends each call to calls.txt. Behaviour, from env:
//   FAKE_FAIL_STEP=<prepare|verify|install>  that step fails
//   FAKE_RECEIVER_AFTER=<n>                   the receiver's line shows on the n-th `logcat -d -s` (0: never)
const FAKE_ADB = `#!/usr/bin/env bash
dir="$(dirname "$0")"
echo "$*" >> "$dir/calls.txt"
case "$*" in
  *"appUpdateStep prepare"*) [ "\${FAKE_FAIL_STEP:-}" = prepare ] && { echo "FAILURES!!!"; exit 0; }; echo "OK (1 test)";;
  *"appUpdateStep verify"*) [ "\${FAKE_FAIL_STEP:-}" = verify ] && { echo "FAILURES!!!"; exit 0; }; echo "OK (1 test)";;
  "install -r -t "*) [ "\${FAKE_FAIL_STEP:-}" = install ] && { echo "Failure"; exit 1; }; echo "Success";;
  "logcat -d -s "*)
    n=$(( $(cat "$dir/polls" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$dir/polls"
    after="\${FAKE_RECEIVER_AFTER:-1}"
    [ "$after" -gt 0 ] && [ "$n" -ge "$after" ] && echo "I AppUpdatedReceiver: handled MY_PACKAGE_REPLACED: refresh restarted";;
  "shell dumpsys jobscheduler "*) echo "JOB #u0a1/1 io.github.veganmarshmallow.taskboard/.widget.BoardJobService";;
  "logcat -d -v time") echo "I/AppUpdatedReceiver: something"; echo "I/Unrelated: noise";;
esac
exit 0
`;

function run(env = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'app-update-'));
  const adb = path.join(dir, 'adb');
  fs.writeFileSync(adb, FAKE_ADB, { mode: 0o755 });
  const r = spawnSync('bash', [script, 'my app.apk'], {
    cwd: dir,
    encoding: 'utf8',
    env: { ...process.env, ADB: adb, POLL_S: '1', RECEIVER_WAIT_S: '5', ...env },
  });
  const calls = fs.existsSync(path.join(dir, 'calls.txt'))
    ? fs.readFileSync(path.join(dir, 'calls.txt'), 'utf8').trim().split('\n') : [];
  const instrument = fs.existsSync(path.join(dir, 'instrument.log'))
    ? fs.readFileSync(path.join(dir, 'instrument.log'), 'utf8') : '';
  return { status: r.status, out: r.stdout + r.stderr, calls, instrument };
}

const index = (calls, re) => calls.findIndex((c) => re.test(c));

test('verify starts only after the receiver handled the update', () => {
  const r = run({ FAKE_RECEIVER_AFTER: '3' });
  assert.strictEqual(r.status, 0, r.out);
  const clear = index(r.calls, /^logcat -c$/);
  const install = index(r.calls, /^install -r -t my app\.apk$/);
  const lastPoll = r.calls.map((c, i) => (/^logcat -d -s/.test(c) ? i : -1)).filter((i) => i >= 0).pop();
  const verify = index(r.calls, /appUpdateStep verify/);
  assert.ok(index(r.calls, /appUpdateStep prepare/) < clear && clear < install && install < lastPoll && lastPoll < verify, r.calls.join('\n'));
  assert.strictEqual(r.calls.filter((c) => /^logcat -d -s/.test(c)).length, 3);
  assert.match(r.out, /App-update recovery passed/);
  assert.doesNotMatch(r.out, /diagnostics/);
});

test('a receiver that never reports still runs verify, after the wait', () => {
  const r = run({ FAKE_RECEIVER_AFTER: '0', RECEIVER_WAIT_S: '2', POLL_S: '0' }); // 0 is clamped to 1
  assert.strictEqual(r.status, 0, r.out);
  assert.match(r.out, /did not report MY_PACKAGE_REPLACED within 2s; running verify anyway/);
  assert.strictEqual(r.calls.filter((c) => /^logcat -d -s/.test(c)).length, 3); // at 0, 1 and 2 s
  assert.ok(index(r.calls, /appUpdateStep verify/) >= 0);
});

test('a failed verify fails the script and prints JobScheduler state and the app logcat', () => {
  const r = run({ FAKE_FAIL_STEP: 'verify' });
  assert.strictEqual(r.status, 1, r.out);
  assert.match(r.instrument, /App-update recovery did not pass/);
  assert.match(r.out, /App-update diagnostics: JobScheduler[\s\S]*BoardJobService/);
  assert.match(r.out, /App-update diagnostics: logcat[\s\S]*AppUpdatedReceiver: something/);
  assert.doesNotMatch(r.out, /Unrelated: noise/);
});

test('a failed prepare skips the install and verify', () => {
  const r = run({ FAKE_FAIL_STEP: 'prepare' });
  assert.strictEqual(r.status, 1, r.out);
  assert.strictEqual(index(r.calls, /^install /), -1);
  assert.strictEqual(index(r.calls, /appUpdateStep verify/), -1);
  assert.match(r.out, /App-update diagnostics/);
});

test('a failed install skips verify', () => {
  const r = run({ FAKE_FAIL_STEP: 'install' });
  assert.strictEqual(r.status, 1, r.out);
  assert.strictEqual(index(r.calls, /appUpdateStep verify/), -1);
});

test('test output goes to instrument.log for the failure annotations', () => {
  const r = run();
  assert.match(r.instrument, /OK \(1 test\)[\s\S]*Success[\s\S]*OK \(1 test\)/);
});

test('the script waits for the line AppUpdatedReceiver really logs, under its tag', () => {
  const sh = fs.readFileSync(script, 'utf8');
  const kt = fs.readFileSync(receiverKt, 'utf8');
  const mark = sh.match(/receiver_mark="([^"]+)"/)[1];
  const tag = sh.match(/receiver_tag="([^"]+)"/)[1];
  assert.ok(kt.includes(`TAG = "${tag}"`), `AppUpdatedReceiver must log under tag ${tag}`);
  assert.ok(kt.includes(`Log.i(TAG, "${mark}`), `AppUpdatedReceiver must log "${mark}"`);
});

test('emulator-tests.sh runs this script on the debug APK and keeps its failure', () => {
  const sh = fs.readFileSync(path.join(__dirname, 'emulator-tests.sh'), 'utf8');
  assert.match(sh, /\.github\/scripts\/app-update-test\.sh app\/build\/outputs\/apk\/debug\/app-debug\.apk \|\| status=1/);
});
