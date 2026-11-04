// Tests for held-runs.js (#64). Run: node --test .github/scripts/
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { dropHeldRuns, isHeldUpdateRun, BOT } = require('./held-runs.js');

const SHA = 'ea4b1ad0000000000000000000000000000000000';

function run(over = {}) {
  return { id: 329, head_sha: SHA, event: 'pull_request', conclusion: 'action_required', status: 'completed', triggering_actor: { login: BOT }, actor: { login: BOT }, ...over };
}

/** [pages]: what each list call returns, in order (the last repeats). [failList]/[failDelete]: errors to throw. */
function fake({ pages = [[]], failList = null, failDelete = null } = {}) {
  const calls = [];
  let i = 0;
  const github = {
    rest: {
      actions: {
        listWorkflowRuns: async (args) => {
          calls.push(['list', args.workflow_id, args.head_sha, args.event, args.status]);
          if (failList) throw failList;
          return { data: { workflow_runs: pages[Math.min(i++, pages.length - 1)] } };
        },
        deleteWorkflowRun: async (args) => { calls.push(['delete', args.run_id]); if (failDelete) throw failDelete; },
      },
    },
  };
  const logs = [];
  const waits = [];
  const drop = (polls = 3) => dropHeldRuns({ github, owner: 'o', repo: 'r', workflow: 'build.yml', sha: SHA, polls, pollMs: 7, wait: async (ms) => waits.push(ms), log: (m) => logs.push(m) });
  return { calls, logs, waits, drop };
}

test('only the bot\'s action_required pull_request run on this head is a held update run', () => {
  assert.ok(isHeldUpdateRun(run(), SHA));
  assert.ok(isHeldUpdateRun(run({ triggering_actor: null }), SHA), 'falls back to actor');
  assert.ok(!isHeldUpdateRun(run({ triggering_actor: { login: 'VeganMarshmallow' } }), SHA), 'a person\'s push stays for them to approve');
  assert.ok(!isHeldUpdateRun(run({ triggering_actor: null, actor: null }), SHA));
  assert.ok(!isHeldUpdateRun(run({ event: 'workflow_dispatch' }), SHA));
  assert.ok(!isHeldUpdateRun(run({ conclusion: 'success' }), SHA));
  assert.ok(!isHeldUpdateRun(run({ conclusion: null, status: 'waiting' }), SHA), 'an environment wait is not ours');
  assert.ok(!isHeldUpdateRun(run({ head_sha: 'other' }), SHA));
  assert.ok(!isHeldUpdateRun(null, SHA));
  assert.ok(!isHeldUpdateRun(undefined, SHA));
});

test('deletes the held run found on the first look, asking GitHub for exactly that filter', async () => {
  const f = fake({ pages: [[run()]] });
  assert.strictEqual(await f.drop(), 1);
  assert.deepStrictEqual(f.calls, [['list', 'build.yml', SHA, 'pull_request', 'action_required'], ['delete', 329]]);
  assert.deepStrictEqual(f.waits, [], 'no wait before the first look');
});

test('waits for a run GitHub registers late, then stops looking', async () => {
  const f = fake({ pages: [[], [], [run({ id: 5 })]] });
  assert.strictEqual(await f.drop(), 1);
  assert.deepStrictEqual(f.waits, [7, 7]);
});

test('gives up after polls looks when none shows up', async () => {
  const f = fake({ pages: [[]] });
  assert.strictEqual(await f.drop(2), 0);
  assert.strictEqual(f.calls.filter((c) => c[0] === 'list').length, 2);
  assert.strictEqual(await fake().drop(0), 0, 'zero polls: no call at all');
});

test('several held runs (e.g. two quick updates) are all deleted; foreign ones are kept', async () => {
  const f = fake({ pages: [[run({ id: 1 }), run({ id: 2, triggering_actor: { login: 'someone' } }), run({ id: 3 })]] });
  assert.strictEqual(await f.drop(), 2);
  assert.deepStrictEqual(f.calls.filter((c) => c[0] === 'delete'), [['delete', 1], ['delete', 3]]);
});

test('a list page without workflow_runs counts as empty', async () => {
  const f = fake({ pages: [undefined] });
  assert.strictEqual(await f.drop(1), 0);
});

test('API errors are logged, never thrown: the Merge check still ignores the held run', async () => {
  const forbidden = Object.assign(new Error('Resource not accessible by integration'), { status: 403 });
  const list = fake({ failList: forbidden });
  assert.strictEqual(await list.drop(), 0);
  assert.match(list.logs[0], /couldn't drop the held run on ea4b1ad: 403/);
  const del = fake({ pages: [[run()]], failDelete: forbidden });
  assert.strictEqual(await del.drop(), 0);
  assert.strictEqual(del.logs.length, 1);
});
