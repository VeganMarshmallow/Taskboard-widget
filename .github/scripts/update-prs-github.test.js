// Tests for update-prs-github.js (#84): each GitHub call alone, against a minimal fake Octokit.
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const gh = require('./update-prs-github.js');

function http(status, message) {
  return Object.assign(new Error(message), { status, response: { data: { message } } });
}

/** A context whose Octokit records every call as [name, args]; [impl] overrides one call by name. */
function ctx(impl = {}, extra = {}) {
  const calls = [];
  const logs = [];
  const call = (name, fallback) => async (args) => { calls.push([name, args]); return (impl[name] || fallback)(args); };
  const github = {
    rest: {
      pulls: {
        list: Symbol('list'),
        update: call('update', () => ({})),
        updateBranch: call('updateBranch', () => ({})),
        get: call('get', () => ({ data: { head: { sha: 'old' } } })),
      },
      repos: { compareCommitsWithBasehead: call('compare', () => ({ data: { behind_by: 0 } })) },
      issues: {
        addLabels: call('addLabels', () => ({})),
        createComment: call('createComment', () => ({})),
        removeLabel: call('removeLabel', () => ({})),
      },
      actions: { createWorkflowDispatch: call('dispatch', () => ({})) },
    },
    paginate: async (method, params) => { calls.push(['paginate', params]); return (impl.paginate || (() => []))(params); },
  };
  return {
    calls, logs,
    ctx: { github, owner: 'o', repo: 'r', main: 'main', workflow: 'build.yml', log: (m) => logs.push(m), wait: async () => {}, headPolls: 3, pollMs: 0, ...extra },
  };
}

const pr = (over = {}) => ({ number: 7, head: { ref: 'claude/7-x', sha: 'old' }, base: { ref: 'claude/6-y' }, ...over });

test('error checks: conflict, workflow refusal and unknown input are told apart', () => {
  assert.ok(gh.isConflict(http(422, 'Merge conflict')));
  assert.ok(!gh.isConflict(http(422, 'expected head sha didn’t match')));
  assert.ok(!gh.isConflict(http(409, 'conflict')));
  assert.ok(!gh.isConflict(null));
  assert.ok(gh.isConflict(Object.assign(new Error('conflict here'), { status: 422 })), 'falls back to error.message');
  assert.ok(gh.isWorkflowRefusal(http(403, 'refusing to allow a GitHub App to create or update workflow')));
  assert.ok(gh.isWorkflowRefusal(http(422, 'workflow scope needed')));
  assert.ok(!gh.isWorkflowRefusal(http(500, 'workflow')));
  assert.ok(!gh.isWorkflowRefusal(undefined));
  assert.ok(gh.isUnknownInput(http(422, 'Unexpected inputs provided: ["full"]')));
  assert.ok(!gh.isUnknownInput(http(422, 'No ref found')));
  assert.ok(!gh.isUnknownInput({}));
});

test('wasBaseMerged: only a merged PR of that branch counts', async () => {
  const merged = ctx({ paginate: () => [{ merged_at: null }, { merged_at: '2026-01-01' }] });
  assert.strictEqual(await gh.wasBaseMerged(merged.ctx, 'claude/6-y'), true);
  assert.deepStrictEqual(merged.calls[0][1], { owner: 'o', repo: 'r', state: 'closed', head: 'o:claude/6-y', per_page: 100 });
  assert.strictEqual(await gh.wasBaseMerged(ctx({ paginate: () => [{ merged_at: null }] }).ctx, 'b'), false);
  assert.strictEqual(await gh.wasBaseMerged(ctx().ctx, 'b'), false, 'no PR at all');
});

test('mergedBaseChecker asks once per branch, and does not cache a failure', async () => {
  let fail = true;
  const { ctx: c, calls } = ctx({ paginate: () => { if (fail) throw new Error('boom'); return [{ merged_at: 'x' }]; } });
  const check = gh.mergedBaseChecker(c);
  await assert.rejects(check('a'), /boom/);
  fail = false;
  assert.strictEqual(await check('a'), true);
  assert.strictEqual(await check('a'), true);
  assert.strictEqual(await check('b'), true);
  assert.strictEqual(calls.filter(([n]) => n === 'paginate').length, 3);
});

test('retarget moves the PR onto main and logs it', async () => {
  const { ctx: c, calls, logs } = ctx();
  await gh.retarget(c, pr());
  assert.deepStrictEqual(calls, [['update', { owner: 'o', repo: 'r', pull_number: 7, base: 'main' }]]);
  assert.deepStrictEqual(logs, ['#7: retargeted from claude/6-y to main']);
});

test('retarget passes a failure through without logging success', async () => {
  const { ctx: c, logs } = ctx({ update: () => { throw http(422, 'base gone'); } });
  await assert.rejects(gh.retarget(c, pr()), /base gone/);
  assert.deepStrictEqual(logs, []);
});

test('behindBy compares main with the head SHA', async () => {
  const { ctx: c, calls } = ctx({ compare: () => ({ data: { behind_by: 4 } }) });
  assert.strictEqual(await gh.behindBy(c, 'abc'), 4);
  assert.strictEqual(calls[0][1].basehead, 'main...abc');
});

test('updateBranch: null on success, pinned to the head it saw', async () => {
  const { ctx: c, calls } = ctx();
  assert.strictEqual(await gh.updateBranch(c, pr()), null);
  assert.deepStrictEqual(calls[0][1], { owner: 'o', repo: 'r', pull_number: 7, expected_head_sha: 'old' });
});

test('updateBranch: a conflict or a workflow refusal is a reason, anything else is thrown', async () => {
  assert.strictEqual(await gh.updateBranch(ctx({ updateBranch: () => { throw http(422, 'merge conflict'); } }).ctx, pr()), 'merge conflict');
  assert.match(await gh.updateBranch(ctx({ updateBranch: () => { throw http(403, 'workflow scope'); } }).ctx, pr()), /`main` changed workflow files/);
  await assert.rejects(gh.updateBranch(ctx({ updateBranch: () => { throw http(422, 'head moved'); } }).ctx, pr()), /head moved/);
  await assert.rejects(gh.updateBranch(ctx({ updateBranch: () => { throw http(500, 'server'); } }).ctx, pr()), /server/);
});

test('waitForNewHead returns the first new SHA, waiting before each poll', async () => {
  const shas = ['old', 'old', 'new'];
  const waits = [];
  const { ctx: c, calls } = ctx({ get: () => ({ data: { head: { sha: shas.shift() } } }) }, { wait: async (ms) => waits.push(ms), pollMs: 9 });
  assert.strictEqual(await gh.waitForNewHead(c, 7, 'old'), 'new');
  assert.strictEqual(calls.length, 3);
  assert.deepStrictEqual(waits, [9, 9, 9]);
});

test('waitForNewHead gives up after headPolls polls; zero polls asks nothing', async () => {
  const slow = ctx();
  assert.strictEqual(await gh.waitForNewHead(slow.ctx, 7, 'old'), null);
  assert.strictEqual(slow.calls.length, 3);
  const none = ctx({}, { headPolls: 0 });
  assert.strictEqual(await gh.waitForNewHead(none.ctx, 7, 'old'), null);
  assert.strictEqual(none.calls.length, 0);
});

test('markNeedsRebase labels and comments once', async () => {
  const fresh = ctx();
  await gh.markNeedsRebase(fresh.ctx, pr(), [], 'merge conflict');
  assert.deepStrictEqual(fresh.calls.map(([n]) => n), ['addLabels', 'createComment']);
  assert.deepStrictEqual(fresh.calls[0][1].labels, [gh.LABEL]);
  assert.match(fresh.calls[1][1].body, /with `main`: merge conflict\. Labelled `needs-rebase`/);
  const already = ctx();
  await gh.markNeedsRebase(already.ctx, pr(), ['bug', gh.LABEL], 'merge conflict');
  assert.strictEqual(already.calls.length, 0);
});

test('clearLabel removes only a label that is there, and ignores a failed removal', async () => {
  const absent = ctx();
  await gh.clearLabel(absent.ctx, 7, ['bug']);
  assert.strictEqual(absent.calls.length, 0);
  const present = ctx({ removeLabel: () => Promise.reject(http(404, 'Label does not exist')) });
  await gh.clearLabel(present.ctx, 7, [gh.LABEL]);
  assert.deepStrictEqual(present.calls, [['removeLabel', { owner: 'o', repo: 'r', issue_number: 7, name: gh.LABEL }]]);
});

test('dispatch sends full as a string input', async () => {
  const { ctx: c, calls } = ctx();
  await gh.dispatch(c, 'claude/7-x', false);
  assert.deepStrictEqual(calls, [['dispatch', { owner: 'o', repo: 'r', workflow_id: 'build.yml', ref: 'claude/7-x', inputs: { full: 'false' } }]]);
});

test('dispatch retries without inputs only when the branch build.yml lacks them', async () => {
  const old = ctx({ dispatch: (args) => { if (args.inputs) throw http(422, 'Unexpected inputs provided: ["full"]'); } });
  await gh.dispatch(old.ctx, 'b', true);
  assert.deepStrictEqual(old.calls.map(([, a]) => a.inputs), [{ full: 'true' }, undefined]);
  const other = ctx({ dispatch: () => { throw http(422, 'No ref found for: b'); } });
  await assert.rejects(gh.dispatch(other.ctx, 'b', true), /No ref found/);
  assert.strictEqual(other.calls.length, 1);
});
