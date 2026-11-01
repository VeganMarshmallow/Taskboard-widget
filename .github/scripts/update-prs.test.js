// Tests for update-prs.js (#45) against a fake Octokit. Run: node --test .github/scripts/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { updatePrs } = require('./update-prs.js');

const OWNER = 'VeganMarshmallow';
const REPO = 'Taskboard-widget';
const FULL = `${OWNER}/${REPO}`;

function pr(number, { head = `claude/${number}-x`, base = 'main', sha = `sha${number}`, labels = [], fork = false, draft = false } = {}) {
  return { number, draft, head: { ref: head, sha, repo: { full_name: fork ? 'someone/fork' : FULL } }, base: { ref: base }, labels: labels.map((name) => ({ name })) };
}

function http(status, message) {
  return Object.assign(new Error(message), { status, response: { data: { message } } });
}

/** A fake Octokit recording every write. [behind]: behind_by per head SHA; [conflicts]: PRs whose update fails. */
function fake({ open, closed = [], behind = {}, conflicts = [], updateError = {}, headMoves = true, retargetError = null, dispatchError = null, rejectInputs = false }) {
  const calls = [];
  const inputs = {}; // dispatch inputs per branch
  const counts = { paginated: 0 };
  const heads = {};
  const rest = {
    pulls: {
      list: Symbol('list'),
      get: async ({ pull_number }) => ({ data: { head: { sha: heads[pull_number] || `old${pull_number}` } } }),
      update: async (args) => { calls.push(['retarget', args.pull_number, args.base]); if (retargetError) throw retargetError; },
      updateBranch: async (args) => {
        calls.push(['update', args.pull_number, args.expected_head_sha]);
        if (conflicts.includes(args.pull_number)) throw http(422, 'merge conflict between base and head');
        if (updateError[args.pull_number]) throw updateError[args.pull_number];
        if (headMoves) heads[args.pull_number] = `new${args.pull_number}`;
        else heads[args.pull_number] = open.find((p) => p.number === args.pull_number).head.sha;
      },
    },
    repos: {
      compareCommitsWithBasehead: async ({ basehead }) => {
        const sha = basehead.split('...')[1];
        return { data: { behind_by: behind[sha] ?? 0 } };
      },
    },
    issues: {
      addLabels: async (args) => { calls.push(['label', args.issue_number, args.labels[0]]); },
      createComment: async (args) => { calls.push(['comment', args.issue_number]); },
      removeLabel: async (args) => { calls.push(['unlabel', args.issue_number, args.name]); },
    },
    actions: {
      createWorkflowDispatch: async (args) => {
        if (rejectInputs && args.inputs) throw http(422, 'Unexpected inputs provided: ["full"]');
        calls.push(['dispatch', args.workflow_id, args.ref]); inputs[args.ref] = args.inputs; if (dispatchError) throw dispatchError;
      },
    },
  };
  // Every PR that pulls.get reports keeps the old SHA until updated.
  for (const p of open) heads[p.number] = p.head.sha;
  const github = {
    rest,
    paginate: async (method, params) => {
      assert.strictEqual(method, rest.pulls.list);
      counts.paginated += 1;
      assert.strictEqual(params.per_page, 100);
      if (params.state === 'open') return open;
      const branch = params.head.split(':')[1];
      return closed.filter((p) => p.head.ref === branch);
    },
  };
  const run = () => updatePrs({ github, owner: OWNER, repo: REPO, main: 'main', log: () => {}, wait: async () => {}, headPolls: 3, pollMs: 0 });
  return { calls, counts, run, inputs };
}

test('a PR behind main is updated, then its build is dispatched on its branch', async () => {
  const f = fake({ open: [pr(22)], behind: { sha22: 2 } });
  const results = await f.run();
  assert.deepStrictEqual(results, [{ number: 22, action: 'updated' }]);
  assert.deepStrictEqual(f.calls, [['update', 22, 'sha22'], ['dispatch', 'build.yml', 'claude/22-x']]);
  assert.deepStrictEqual(f.inputs['claude/22-x'], { full: 'true' }, 'a ready PR gets the full run (emulator tests)');
});

test('a draft PR behind main is updated and gets only the quick build (#52)', async () => {
  const f = fake({ open: [pr(22, { draft: true }), pr(23)], behind: { sha22: 1, sha23: 1 } });
  await f.run();
  assert.deepStrictEqual(f.inputs, { 'claude/22-x': { full: 'false' }, 'claude/23-x': { full: 'true' } });
});

test('a PR without a draft field (older API shape) counts as ready and gets the full run', async () => {
  const p = pr(22);
  delete p.draft;
  const f = fake({ open: [p], behind: { sha22: 1 } });
  await f.run();
  assert.deepStrictEqual(f.inputs['claude/22-x'], { full: 'true' });
});

test('an up-to-date PR costs no update and no CI run', async () => {
  const f = fake({ open: [pr(22)] });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'up-to-date' }]);
  assert.deepStrictEqual(f.calls, []);
});

test('a PR stacked on a merged branch is retargeted to main, updated and re-tested', async () => {
  const merged = { ...pr(22), merged_at: '2026-10-31T10:00:00Z' };
  const f = fake({ open: [pr(23, { base: 'claude/22-x' })], closed: [merged], behind: { sha23: 1 } });
  assert.deepStrictEqual(await f.run(), [{ number: 23, action: 'updated' }]);
  assert.deepStrictEqual(f.calls, [['retarget', 23, 'main'], ['update', 23, 'sha23'], ['dispatch', 'build.yml', 'claude/23-x']]);
});

test('a PR stacked on an open PR is left alone', async () => {
  const f = fake({ open: [pr(22), pr(23, { base: 'claude/22-x' })], behind: { sha23: 5 } });
  const results = await f.run();
  assert.deepStrictEqual(results, [{ number: 22, action: 'up-to-date' }, { number: 23, action: 'stacked' }]);
  assert.deepStrictEqual(f.calls, []);
});

test('a base branch closed without merging does not count as merged', async () => {
  const closedUnmerged = { ...pr(22), merged_at: null };
  const f = fake({ open: [pr(23, { base: 'claude/22-x' })], closed: [closedUnmerged] });
  assert.deepStrictEqual(await f.run(), [{ number: 23, action: 'stacked' }]);
});

test('a retargeted PR that is already up to date is not re-run', async () => {
  const merged = { ...pr(22), merged_at: '2026-10-31T10:00:00Z' };
  const f = fake({ open: [pr(23, { base: 'claude/22-x' })], closed: [merged] });
  assert.deepStrictEqual(await f.run(), [{ number: 23, action: 'retargeted' }]);
  assert.deepStrictEqual(f.calls, [['retarget', 23, 'main']]);
});

test('a conflict labels needs-rebase and comments once, without a CI run', async () => {
  const f = fake({ open: [pr(22), pr(24, { labels: ['needs-rebase'] })], behind: { sha22: 1, sha24: 1 }, conflicts: [22, 24] });
  const results = await f.run();
  assert.deepStrictEqual(results, [{ number: 22, action: 'conflict' }, { number: 24, action: 'conflict' }]);
  assert.deepStrictEqual(f.calls, [['update', 22, 'sha22'], ['label', 22, 'needs-rebase'], ['comment', 22], ['update', 24, 'sha24']]);
});

test('a successful update clears a stale needs-rebase label', async () => {
  const f = fake({ open: [pr(22, { labels: ['needs-rebase'] })], behind: { sha22: 1 } });
  await f.run();
  assert.deepStrictEqual(f.calls.map((c) => c[0]), ['update', 'unlabel', 'dispatch']);
});

test('other update errors are reported and do not stop the other PRs', async () => {
  const f = fake({ open: [pr(22), pr(23)], behind: { sha22: 1, sha23: 1 }, updateError: { 22: http(422, 'expected head sha did not match') } });
  const results = await f.run();
  assert.strictEqual(results[0].action, 'error');
  assert.deepStrictEqual(results[1], { number: 23, action: 'updated' });
  assert.ok(!f.calls.some((c) => c[0] === 'label'), 'not mistaken for a conflict');
});

test('the build is still dispatched when the new head is slow to appear', async () => {
  const f = fake({ open: [pr(22)], behind: { sha22: 1 }, headMoves: false });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'update-pending' }]);
  assert.deepStrictEqual(f.calls.at(-1), ['dispatch', 'build.yml', 'claude/22-x']);
});

test('only claude/* branches of this repo are touched', async () => {
  const f = fake({ open: [pr(5, { head: 'feature/x' }), pr(6, { fork: true }), pr(7, { head: 'claude/7-x', fork: false })], behind: { sha5: 1, sha6: 1, sha7: 1 } });
  const results = await f.run();
  assert.deepStrictEqual(results.map((r) => r.number), [7]);
});

test('no open PRs: nothing to do', async () => {
  const f = fake({ open: [] });
  assert.deepStrictEqual(await f.run(), []);
});

// Reviewer edge cases (#45).

test('a workflow-file refusal is handed to Claude like a conflict, not retried', async () => {
  const refusal = http(403, 'refusing to allow a GitHub App to create or update workflow `.github/workflows/x.yml` without `workflows` permission');
  const f = fake({ open: [pr(22)], behind: { sha22: 1 }, updateError: { 22: refusal } });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'conflict' }]);
  assert.deepStrictEqual(f.calls, [['update', 22, 'sha22'], ['label', 22, 'needs-rebase'], ['comment', 22]]);
});

test('an up-to-date PR loses a stale needs-rebase label, with no CI run', async () => {
  const f = fake({ open: [pr(22, { labels: ['needs-rebase'] })] });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'up-to-date' }]);
  assert.deepStrictEqual(f.calls, [['unlabel', 22, 'needs-rebase']]);
});

test('a failed retarget (e.g. base branch deleted) is an error and the next PR still runs', async () => {
  const merged = { ...pr(22), merged_at: '2026-10-31T10:00:00Z' };
  const f = fake({ open: [pr(23, { base: 'claude/22-x' }), pr(24)], closed: [merged], behind: { sha24: 1 }, retargetError: http(422, 'Validation Failed') });
  const results = await f.run();
  assert.strictEqual(results[0].action, 'error');
  assert.deepStrictEqual(results[1], { number: 24, action: 'updated' });
  assert.ok(!f.calls.some((c) => c[0] === 'update' && c[1] === 23), 'not updated against the old base');
});

test('a failed dispatch is reported as an error', async () => {
  const f = fake({ open: [pr(22)], behind: { sha22: 1 }, dispatchError: http(403, 'Resource not accessible by integration') });
  const results = await f.run();
  assert.strictEqual(results[0].action, 'error');
  assert.match(results[0].error, /not accessible/);
});

test('a branch whose build.yml has no `full` input yet is dispatched without inputs (#52)', async () => {
  const f = fake({ open: [pr(22)], behind: { sha22: 1 }, rejectInputs: true });
  const results = await f.run();
  assert.deepStrictEqual(results, [{ number: 22, action: 'updated' }]);
  assert.deepStrictEqual(f.calls.at(-1), ['dispatch', 'build.yml', 'claude/22-x']);
  assert.strictEqual(f.inputs['claude/22-x'], undefined);
});

test('other 422s from the dispatch are not retried', async () => {
  const f = fake({ open: [pr(22)], behind: { sha22: 1 }, dispatchError: http(422, 'No ref found for: claude/22-x') });
  const results = await f.run();
  assert.strictEqual(results[0].action, 'error');
  assert.strictEqual(f.calls.filter((c) => c[0] === 'dispatch').length, 1);
});

test('more than 30 open PRs are all handled through pagination', async () => {
  const open = Array.from({ length: 35 }, (_, i) => pr(100 + i));
  const f = fake({ open, behind: { sha134: 1 } });
  const results = await f.run();
  assert.strictEqual(results.length, 35);
  assert.deepStrictEqual(results.at(-1), { number: 134, action: 'updated' });
  assert.strictEqual(f.counts.paginated, 1);
});

test('a PR whose fork was deleted (head.repo null) is skipped', async () => {
  const gone = pr(9); gone.head.repo = null;
  const f = fake({ open: [gone], behind: { sha9: 1 } });
  assert.deepStrictEqual(await f.run(), []);
});

test('conflict detection: 422 conflict vs other 422 vs other statuses', () => {
  const { isConflict, isWorkflowRefusal } = require('./update-prs.js');
  assert.ok(isConflict(http(422, 'merge conflict between base and head')));
  assert.ok(!isConflict(http(422, 'expected head sha did not match')));
  assert.ok(!isConflict(http(409, 'merge conflict')));
  assert.ok(!isConflict(null));
  assert.ok(isWorkflowRefusal(http(403, 'without `workflows` permission')));
  assert.ok(!isWorkflowRefusal(http(403, 'Resource not accessible by integration')));
});
