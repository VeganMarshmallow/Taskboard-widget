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
function fake({ open, closed = [], behind = {}, conflicts = [], updateError = {}, headMoves = true, retargetError = null, dispatchError = null, rejectInputs = false, held = {} }) {
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
      // held: runs GitHub lists per head SHA (#64); the fake honours the filters the script sends.
      listWorkflowRuns: async (args) => {
        counts.listed = (counts.listed || 0) + 1;
        const runs = (held[args.head_sha] || []).filter((r) => r.event === args.event && r.conclusion === args.status);
        return { data: { total_count: runs.length, workflow_runs: runs } };
      },
      deleteWorkflowRun: async (args) => { calls.push(['drop', args.run_id]); },
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
  const run = (extra = {}) => updatePrs({ github, owner: OWNER, repo: REPO, main: 'main', log: () => {}, wait: async () => {}, headPolls: 3, heldPolls: 2, pollMs: 0, ...extra });
  return { calls, counts, run, inputs };
}

test('a PR behind main is updated, then its build is dispatched on its branch', async () => {
  const f = fake({ open: [pr(22)], behind: { sha22: 2 } });
  const results = await f.run();
  assert.deepStrictEqual(results, [{ number: 22, action: 'updated' }]);
  assert.deepStrictEqual(f.calls, [['update', 22, 'sha22'], ['dispatch', 'build.yml', 'claude/22-x']]);
  assert.deepStrictEqual(f.inputs['claude/22-x'], { full: 'true' }, 'a ready PR gets the full run (emulator tests)');
});

test('a draft PR is not updated: only the next mergeable PR is (#79)', async () => {
  const f = fake({ open: [pr(22, { draft: true }), pr(23)], behind: { sha22: 1, sha23: 1 } });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'draft' }, { number: 23, action: 'updated' }]);
  assert.deepStrictEqual(f.inputs, { 'claude/23-x': { full: 'true' } });
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

test('more than 30 open PRs are all listed through pagination', async () => {
  const open = Array.from({ length: 35 }, (_, i) => pr(100 + i));
  const f = fake({ open, behind: { sha100: 1, sha134: 1 } });
  const results = await f.run();
  assert.strictEqual(results.length, 35);
  assert.deepStrictEqual(results[0], { number: 100, action: 'updated' });
  assert.deepStrictEqual(results.at(-1), { number: 134, action: 'queued' });
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

// --- #64: the pull_request run GitHub holds for approval after a GITHUB_TOKEN update ---

function heldRun(id, sha, { by = 'github-actions[bot]', event = 'pull_request', conclusion = 'action_required' } = {}) {
  return { id, head_sha: sha, event, conclusion, status: 'completed', triggering_actor: { login: by }, actor: { login: by } };
}

test('the held pull_request run on the new head is deleted after the dispatch: one real CI run per update', async () => {
  const f = fake({ open: [pr(45)], behind: { sha45: 1 }, held: { new45: [heldRun(329, 'new45')] } });
  assert.deepStrictEqual(await f.run(), [{ number: 45, action: 'updated' }]);
  assert.deepStrictEqual(f.calls, [['update', 45, 'sha45'], ['dispatch', 'build.yml', 'claude/45-x'], ['drop', 329]]);
});

test('held runs started by someone else, other events or the old head are left alone', async () => {
  const f = fake({
    open: [pr(45)],
    behind: { sha45: 1 },
    held: { new45: [heldRun(1, 'new45', { by: 'VeganMarshmallow' }), heldRun(2, 'new45', { event: 'push' }), heldRun(3, 'new45', { conclusion: 'success' })], sha45: [heldRun(4, 'sha45')] },
  });
  await f.run();
  assert.ok(!f.calls.some((c) => c[0] === 'drop'), JSON.stringify(f.calls));
  assert.strictEqual(f.counts.listed, 2, 'looks heldPolls times, then gives up');
});

test('no held run to look for when the head has not moved yet, or nothing was updated', async () => {
  const slow = fake({ open: [pr(22)], behind: { sha22: 1 }, headMoves: false, held: { sha22: [heldRun(9, 'sha22')] } });
  await slow.run();
  assert.ok(!slow.counts.listed, 'the old head is never touched');
  const upToDate = fake({ open: [pr(22)] });
  await upToDate.run();
  assert.ok(!upToDate.counts.listed);
});

// --- #79: only the next PR in merge order is updated and re-tested ---

test('only the next PR (the oldest) is updated and re-tested; the others wait their turn', async () => {
  const f = fake({ open: [pr(24), pr(22), pr(23)], behind: { sha22: 1, sha23: 1, sha24: 1 } });
  assert.deepStrictEqual(await f.run(), [{ number: 24, action: 'queued' }, { number: 22, action: 'updated' }, { number: 23, action: 'queued' }]);
  assert.deepStrictEqual(f.calls, [['update', 22, 'sha22'], ['dispatch', 'build.yml', 'claude/22-x']]);
});

test('an up-to-date next PR needs nothing, and nothing after it is updated', async () => {
  const f = fake({ open: [pr(22), pr(23)], behind: { sha23: 4 } });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'up-to-date' }, { number: 23, action: 'queued' }]);
  assert.deepStrictEqual(f.calls, []);
});

test('a conflicting next PR is labelled and the one after it is updated instead', async () => {
  const f = fake({ open: [pr(22), pr(23), pr(24)], behind: { sha22: 1, sha23: 1, sha24: 1 }, conflicts: [22] });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'conflict' }, { number: 23, action: 'updated' }, { number: 24, action: 'queued' }]);
  assert.deepStrictEqual(f.calls.map((c) => c.slice(0, 2)), [['update', 22], ['label', 22], ['comment', 22], ['update', 23], ['dispatch', 'build.yml']]);
});

test('a PR stacked on the branch just merged is next, ahead of older PRs (Merge all order)', async () => {
  const merged = { ...pr(21), merged_at: '2026-11-07T10:00:00Z' };
  const f = fake({ open: [pr(20), pr(25, { base: 'claude/21-x' })], closed: [merged], behind: { sha20: 1, sha25: 1 } });
  assert.deepStrictEqual(await f.run(), [{ number: 20, action: 'queued' }, { number: 25, action: 'updated' }]);
  assert.deepStrictEqual(f.calls.map((c) => c[0]), ['retarget', 'update', 'dispatch']);
});

test('only PRs that are all stacked or drafts: nothing is updated', async () => {
  const f = fake({ open: [pr(22, { draft: true }), pr(23, { base: 'claude/22-x' })], behind: { sha22: 1, sha23: 1 } });
  assert.deepStrictEqual(await f.run(), [{ number: 22, action: 'draft' }, { number: 23, action: 'stacked' }]);
  assert.deepStrictEqual(f.calls, []);
});

test('every PR conflicting: each is labelled, none dispatched', async () => {
  const f = fake({ open: [pr(22), pr(23)], behind: { sha22: 1, sha23: 1 }, conflicts: [22, 23] });
  assert.deepStrictEqual((await f.run()).map((r) => r.action), ['conflict', 'conflict']);
  assert.ok(!f.calls.some((c) => c[0] === 'dispatch'));
});

// --- reviewer (#79): edge cases of the next-only update ---

test('PRs stacked on the merged branch but not updated this run are still retargeted (no CI started)', async () => {
  const merged = { ...pr(21), merged_at: '2026-11-07T10:00:00Z' };
  const f = fake({
    open: [pr(25, { base: 'claude/21-x' }), pr(26, { base: 'claude/21-x' }), pr(27, { base: 'claude/21-x', draft: true })],
    closed: [merged], behind: { sha25: 1, sha26: 1, sha27: 1 },
  });
  assert.deepStrictEqual(await f.run(), [{ number: 25, action: 'updated' }, { number: 26, action: 'queued' }, { number: 27, action: 'draft' }]);
  assert.deepStrictEqual(f.calls.filter((c) => c[0] === 'retarget').map((c) => c[1]).sort(), [25, 26, 27]);
  assert.deepStrictEqual(f.calls.filter((c) => c[0] === 'update' || c[0] === 'dispatch').map((c) => c[0]), ['update', 'dispatch']);
});

test('a PR stacked on a still-open PR is not retargeted even when queued', async () => {
  const f = fake({ open: [pr(22), pr(23), pr(24, { base: 'claude/23-x' })], behind: { sha22: 1, sha23: 1, sha24: 1 } });
  await f.run();
  assert.ok(!f.calls.some((c) => c[0] === 'retarget'));
});

test('a failed retarget of a waiting PR is reported as an error, the update still happened', async () => {
  const merged = { ...pr(21), merged_at: '2026-11-07T10:00:00Z' };
  const f = fake({ open: [pr(20), pr(27, { base: 'claude/21-x', draft: true })], closed: [merged], behind: { sha20: 1 }, retargetError: http(500, 'boom') });
  const results = await f.run();
  assert.deepStrictEqual(results[0], { number: 20, action: 'updated' });
  assert.strictEqual(results[1].action, 'error');
});

test('an error on the next PR (not a conflict) moves on to the one after it', async () => {
  const f = fake({ open: [pr(22), pr(23)], behind: { sha22: 1, sha23: 1 }, updateError: { 22: http(500, 'server error') } });
  assert.deepStrictEqual((await f.run()).map((r) => r.action), ['error', 'updated']);
});

test('no open PRs at all: nothing is called', async () => {
  const f = fake({ open: [] });
  assert.deepStrictEqual(await f.run(), []);
  assert.deepStrictEqual(f.calls, []);
});

test('an unknown mode fails loudly before any write', async () => {
  const f = fake({ open: [pr(22)], behind: { sha22: 1 } });
  await assert.rejects(f.run({ mode: 'fast' }), /unknown update mode/);
  assert.deepStrictEqual(f.calls, []);
});
