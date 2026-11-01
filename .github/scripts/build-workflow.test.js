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
