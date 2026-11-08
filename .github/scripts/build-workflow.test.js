// Guards for .github/workflows/build.yml (#52). Run: node --test .github/scripts/*.test.js
// The "full run" condition is written twice (run-name can't read env); the Merge button trusts the
// run-name's "[partial]" marker, so the two copies must stay the same condition.
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const yml = fs.readFileSync(path.join(__dirname, '..', 'workflows', 'build.yml'), 'utf8');
const squash = (s) => s.replace(/\s+/g, '');

/** The text of a `>-` block scalar starting at [key] (indented continuation lines). */
function block(key) {
  const m = yml.match(new RegExp(`^(\\s*)${key}: >-\\n((?:\\1\\s+.*\\n)+)`, 'm'));
  assert.ok(m, `${key} block not found`);
  return m[2];
}

test('run-name marks exactly the runs that FULL_CI calls quick', () => {
  const full = squash(block('FULL_CI')).replace(/^\$\{\{(.*)\}\}$/, '$1');
  const runName = squash(block('run-name'));
  assert.ok(full.includes("github.event_name=='pull_request'&&!github.event.pull_request.draft"), full);
  assert.ok(runName.includes(`(!(${full}))&&'[partial]'`), `run-name condition differs from FULL_CI:\n${runName}\n${full}`);
});

test('the marker run-name adds is the one the Merge button reads (WorkflowRuns.PARTIAL_MARK)', () => {
  const kt = fs.readFileSync(path.join(__dirname, '..', '..', 'core/src/main/kotlin/io/github/veganmarshmallow/taskboard/core/merge/WorkflowRuns.kt'), 'utf8');
  const mark = kt.match(/PARTIAL_MARK = "([^"]+)"/)[1];
  assert.ok(block('run-name').includes(`' ${mark}'`), 'run-name must end titles with " " + PARTIAL_MARK');
});

test('runs on main are never cancelled and push/pull_request runs of one commit never cancel each other', () => {
  const group = squash(block('group'));
  assert.ok(group.startsWith("${{github.ref=='refs/heads/main'&&format('build-main-{0}',github.run_id)"), group);
  assert.ok(group.includes('github.event_name'), 'the group must include the event');
});

test('emulator jobs only run in full runs', () => {
  for (const job of ['emulator', 'emulator-recent']) {
    const m = yml.match(new RegExp(`^  ${job}:\\n((?:    .*\\n|\\s*\\n)+)`, 'm'));
    assert.ok(m, job);
    assert.match(m[1], /^    if: needs\.build\.outputs\.full == 'true'$/m, job);
  }
});

// #54: both emulator jobs set up Java, Gradle, KVM and the cached AVD through one composite action.
const actionPath = path.join(__dirname, '..', 'actions', 'avd-setup', 'action.yml');
const action = fs.readFileSync(actionPath, 'utf8');

/** The body of a top-level job (its indented lines). */
function job(name) {
  const m = yml.match(new RegExp(`^  ${name}:\\n((?:    .*\\n|\\s*\\n)+)`, 'm'));
  assert.ok(m, `job ${name} not found`);
  return m[1];
}

/** Input names the composite action declares, with whether they are required. */
function declaredInputs() {
  const m = action.match(/^inputs:\n((?:  .*\n|\s*\n)+)/m);
  assert.ok(m, 'inputs block not found');
  return Object.fromEntries([...m[1].matchAll(/^  ([\w-]+):\n((?:    .*\n)+)/gm)]
    .map(([, name, body]) => [name, /^    required: true$/m.test(body)]));
}

/** The `with:` inputs a job passes to the avd-setup step. */
function passedInputs(body) {
  const m = body.match(/- uses: \.\/\.github\/actions\/avd-setup\n(?:        if: .*\n)?        with:\n((?:          .*\n)+)/);
  assert.ok(m, 'avd-setup step with inputs not found');
  return Object.fromEntries([...m[1].matchAll(/^          ([\w-]+): (.*)$/gm)].map(([, k, v]) => [k, v]));
}

test('both emulator jobs call avd-setup after checkout and no longer repeat its steps', () => {
  for (const name of ['emulator', 'emulator-recent']) {
    const body = job(name);
    const checkout = body.indexOf('- uses: actions/checkout@');
    const setup = body.indexOf('- uses: ./.github/actions/avd-setup');
    assert.ok(checkout >= 0 && setup > checkout, `${name}: checkout must come before avd-setup`);
    for (const moved of ['actions/setup-java', 'gradle/actions/setup-gradle', 'Enable KVM', 'Free disk space', 'actions/cache/']) {
      assert.ok(!body.includes(moved), `${name} still has ${moved}`);
    }
  }
});

test('every avd-setup input is used by the action, and the jobs pass only declared ones', () => {
  const inputs = declaredInputs();
  assert.deepStrictEqual(Object.keys(inputs).sort(), ['api-level', 'cache-key', 'emulator-boot-timeout', 'ram-size']);
  const steps = action.slice(action.indexOf('\nruns:'));
  for (const name of Object.keys(inputs)) {
    assert.ok(steps.includes(`\${{ inputs.${name} }}`), `input ${name} is never used`);
  }
  for (const name of ['emulator', 'emulator-recent']) {
    const passed = passedInputs(job(name));
    for (const k of Object.keys(passed)) assert.ok(k in inputs, `${name} passes undeclared input ${k}`);
    for (const [k, req] of Object.entries(inputs)) if (req) assert.ok(k in passed, `${name} misses required ${k}`);
  }
});

test('avd-setup keeps the jobs\' AVD settings and cache keys', () => {
  const api30 = passedInputs(job('emulator'));
  assert.deepStrictEqual(api30, { 'api-level': '30', 'cache-key': '${{ env.AVD_KEY }}' });
  assert.match(job('emulator'), /^      AVD_KEY: avd-30-google_apis-x86_64-pixel_5-v2$/m);
  const api35 = passedInputs(job('emulator-recent'));
  assert.deepStrictEqual(api35, { 'api-level': '35', 'ram-size': '4096M', 'emulator-boot-timeout': '1200', 'cache-key': '${{ env.AVD_KEY }}' });
  assert.match(job('emulator-recent'), /^      AVD_KEY: avd-35-google_apis-x86_64-pixel_5-4096M-v1$/m);
  assert.match(job('emulator-recent'), /- uses: \.\/\.github\/actions\/avd-setup\n        if: steps\.scope\.outputs\.run == 'true'\n/);
  // The test step's emulator must match the snapshot the action created.
  const runner = /uses: reactivecircus\/android-emulator-runner@v2\n\s+with:.*\n((?:\s{10}.*\n)+)/;
  const tests30 = job('emulator').match(runner)[1];
  assert.match(tests30, /^\s+api-level: 30$/m);
  assert.doesNotMatch(tests30, /ram-size|emulator-boot-timeout/);
  const tests35 = job('emulator-recent').match(runner)[1];
  for (const line of ['api-level: 35', 'ram-size: 4096M', 'emulator-boot-timeout: 1200']) {
    assert.ok(tests35.includes(line), `recent tests step lacks ${line}`);
  }
});

test('avd-setup creates and saves the snapshot on main only, on a cache miss', () => {
  const cond = "if: steps.avd.outputs.cache-hit != 'true' && github.ref == 'refs/heads/main'";
  assert.match(action, /- name: Restore AVD snapshot\n\s+id: avd\n\s+uses: actions\/cache\/restore@v4\n/);
  assert.ok(action.includes(`- name: Create AVD snapshot (main only, on a cache miss)\n      ${cond}\n      uses: reactivecircus/android-emulator-runner@v2`));
  assert.ok(action.includes(`- name: Save AVD snapshot\n      ${cond}\n      uses: actions/cache/save@v4`));
  assert.strictEqual((action.match(/key: \$\{\{ inputs\.cache-key \}\}/g) || []).length, 2);
  // Composite run steps must name their shell.
  const runs = (action.match(/^    - name: .*\n      run: /gm) || []).length;
  assert.strictEqual(runs, 0, 'a run step without shell: bash');
});

test('changing avd-setup puts the recent-Android job in scope', () => {
  assert.ok(job('emulator-recent').includes('|^\\.github/actions/avd-setup/" changed.txt'));
});

test('avd-setup defaults match the emulator runner\'s, so API 30 creates the same snapshot as before', () => {
  // The runner (reactivecircus/android-emulator-runner@v2) defaults emulator-boot-timeout to 600 and
  // has no ram-size default (an unset input reads as ''), as its "with:" listing in the job logs shows.
  const inputs = action.match(/^inputs:\n((?:  .*\n|\s*\n)+)/m)[1];
  assert.match(inputs, /^  ram-size:\n(?:    .*\n)*?    default: ""$/m);
  assert.match(inputs, /^  emulator-boot-timeout:\n(?:    .*\n)*?    default: "600"$/m);
});

test('the workflow no longer reads outputs of steps that moved into avd-setup', () => {
  const ids = [...action.matchAll(/^      id: ([\w-]+)$/gm)].map(([, id]) => id);
  assert.ok(ids.includes('avd'));
  for (const id of ids) {
    assert.ok(!yml.includes(`steps.${id}.`), `build.yml still references steps.${id}`);
  }
});

test('update-prs keeps the Actions write permission it needs to dispatch builds and drop held runs (#64)', () => {
  const update = fs.readFileSync(path.join(__dirname, '..', 'workflows', 'update-prs.yml'), 'utf8');
  assert.match(update, /^\s+actions: write\b/m);
  assert.match(update, /sparse-checkout: \.github\/scripts/, 'held-runs.js must be checked out next to update-prs.js');
});

test('update-prs takes its mode from the merge commit trailer (#83, work pace)', () => {
  const update = fs.readFileSync(path.join(__dirname, '..', 'workflows', 'update-prs.yml'), 'utf8');
  assert.match(update, /modeForCommit\(context\.payload\.head_commit && context\.payload\.head_commit\.message\)/);
  assert.match(update, /updatePrs\(\{\s*mode,/);
  assert.doesNotMatch(update, /secrets\./, 'no stored secret: the setting travels in the merge commit');
});
