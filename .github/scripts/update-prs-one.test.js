// Tests for update-prs-one.js (#84): one PR's update, step by step, against a minimal fake Octokit.
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { updateOne } = require('./update-prs-one.js');

function http(status, message) {
  return Object.assign(new Error(message), { status, response: { data: { message } } });
}

/** A fake recording each write in order. [behind]: behind_by; [updateError]: thrown by update-branch. */
function setup({ behind = 2, updateError = null, headMoves = true, held = [], retargetError = null } = {}) {
  const calls = [];
  const logs = [];
  let head = 'old';
  const github = {
    rest: {
      pulls: {
        update: async (a) => { calls.push(['retarget', a.base]); if (retargetError) throw retargetError; },
        updateBranch: async () => { calls.push(['update']); if (updateError) throw updateError; if (headMoves) head = 'new1234567'; },
        get: async () => ({ data: { head: { sha: head } } }),
      },
      repos: { compareCommitsWithBasehead: async () => { calls.push(['compare']); return { data: { behind_by: behind } }; } },
      issues: {
        addLabels: async () => { calls.push(['label']); },
        createComment: async () => { calls.push(['comment']); },
        removeLabel: async () => { calls.push(['unlabel']); },
      },
      actions: {
        createWorkflowDispatch: async (a) => { calls.push(['dispatch', a.ref, a.inputs.full]); },
        listWorkflowRuns: async () => ({ data: { total_count: held.length, workflow_runs: held } }),
        deleteWorkflowRun: async (a) => { calls.push(['drop', a.run_id]); },
      },
    },
  };
  const ctx = { github, owner: 'o', repo: 'r', main: 'main', workflow: 'build.yml', log: (m) => logs.push(m), wait: async () => {}, headPolls: 2, heldPolls: 1, pollMs: 0 };
  return { ctx, calls, logs };
}

const pr = ({ base = 'main', labels = [], draft = false } = {}) =>
  ({ number: 5, draft, head: { ref: 'claude/5-x', sha: 'old' }, base: { ref: base }, labels: labels.map((name) => ({ name })) });
const never = async () => assert.fail('baseWasMerged should not be asked for a PR on main');

test('a PR on main behind it is updated, dispatched in full and logged', async () => {
  const { ctx, calls, logs } = setup();
  assert.deepStrictEqual(await updateOne(ctx, pr(), { baseWasMerged: never }), { number: 5, action: 'updated' });
  assert.deepStrictEqual(calls, [['compare'], ['update'], ['dispatch', 'claude/5-x', 'true']]);
  assert.match(logs[0], /^#5: updated with main \(now new1234\); build\.yml dispatched \(full\)$/);
});

test('a draft gets the quick build only', async () => {
  const { ctx, calls, logs } = setup();
  await updateOne(ctx, pr({ draft: true }), { baseWasMerged: never });
  assert.deepStrictEqual(calls.at(-1), ['dispatch', 'claude/5-x', 'false']);
  assert.match(logs[0], /build only, draft/);
});

test('stacked on an unmerged branch: nothing is touched', async () => {
  const { ctx, calls } = setup();
  const asked = [];
  const result = await updateOne(ctx, pr({ base: 'claude/4-y' }), { baseWasMerged: async (b) => { asked.push(b); return false; } });
  assert.deepStrictEqual(result, { number: 5, action: 'stacked' });
  assert.deepStrictEqual(asked, ['claude/4-y']);
  assert.deepStrictEqual(calls, []);
});

test('stacked on a merged branch: retargeted, then updated', async () => {
  const { ctx, calls } = setup();
  assert.strictEqual((await updateOne(ctx, pr({ base: 'claude/4-y' }), { baseWasMerged: async () => true })).action, 'updated');
  assert.deepStrictEqual(calls.slice(0, 3), [['retarget', 'main'], ['compare'], ['update']]);
});

test('retargeted and already up to date: retargeted, with no CI run', async () => {
  const { ctx, calls } = setup({ behind: 0 });
  assert.strictEqual((await updateOne(ctx, pr({ base: 'claude/4-y' }), { baseWasMerged: async () => true })).action, 'retargeted');
  assert.deepStrictEqual(calls, [['retarget', 'main'], ['compare']]);
});

test('up to date: a stale needs-rebase label is cleared, nothing else', async () => {
  const { ctx, calls } = setup({ behind: 0 });
  assert.strictEqual((await updateOne(ctx, pr({ labels: ['needs-rebase'] }), { baseWasMerged: never })).action, 'up-to-date');
  assert.deepStrictEqual(calls, [['compare'], ['unlabel']]);
});

test('a conflict labels and comments, logs why, and dispatches nothing', async () => {
  const { ctx, calls, logs } = setup({ updateError: http(422, 'Merge conflict') });
  assert.deepStrictEqual(await updateOne(ctx, pr(), { baseWasMerged: never }), { number: 5, action: 'conflict' });
  assert.deepStrictEqual(calls, [['compare'], ['update'], ['label'], ['comment']]);
  assert.deepStrictEqual(logs, ['#5: merge conflict; labelled needs-rebase']);
});

test('a conflict on a PR already labelled does not comment again', async () => {
  const { ctx, calls } = setup({ updateError: http(422, 'Merge conflict') });
  await updateOne(ctx, pr({ labels: ['needs-rebase'] }), { baseWasMerged: never });
  assert.deepStrictEqual(calls, [['compare'], ['update']]);
});

test('other update errors and a failed retarget are thrown to the caller', async () => {
  await assert.rejects(updateOne(setup({ updateError: http(500, 'boom') }).ctx, pr(), { baseWasMerged: never }), /boom/);
  await assert.rejects(updateOne(setup({ retargetError: http(422, 'gone') }).ctx, pr({ base: 'b' }), { baseWasMerged: async () => true }), /gone/);
  await assert.rejects(updateOne(setup().ctx, pr({ base: 'b' }), { baseWasMerged: async () => { throw new Error('list failed'); } }), /list failed/);
});

test('a head slow to move: still dispatched, reported update-pending, no held run looked for', async () => {
  const { ctx, calls, logs } = setup({ headMoves: false, held: [{ id: 1 }] });
  assert.strictEqual((await updateOne(ctx, pr({ labels: ['needs-rebase'] }), { baseWasMerged: never })).action, 'update-pending');
  assert.deepStrictEqual(calls, [['compare'], ['update'], ['unlabel'], ['dispatch', 'claude/5-x', 'true']]);
  assert.match(logs[0], /head not moved yet/);
});

test('the held pull_request run on the new head is dropped and counted in the log', async () => {
  const run = { id: 42, event: 'pull_request', conclusion: 'action_required', head_sha: 'new1234567', actor: { login: 'github-actions[bot]' }, triggering_actor: { login: 'github-actions[bot]' } };
  const { ctx, calls, logs } = setup({ held: [run] });
  await updateOne(ctx, pr(), { baseWasMerged: never });
  assert.deepStrictEqual(calls.at(-1), ['drop', 42]);
  assert.match(logs.at(-1), /deleted 1 held pull_request run\(s\)$/);
});
