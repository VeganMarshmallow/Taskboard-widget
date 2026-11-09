// Keeps Claude's open PRs up to date after every merge to the default branch (#45). Run by
// .github/workflows/update-prs.yml through actions/github-script, which passes an Octokit client.
//
// #79: only the next PR in merge order is updated and re-tested per run (update-prs-select.js
// picks it), not every open PR: CI runs once per merge, and the widget's auto merge merges that PR
// once it is green, whose merge then runs this again for the PR after it. A PR that conflicts is
// labelled and the one after it is tried instead, so the queue never stalls on a conflict.
// #83: that is mode `next` (work pace Save usage). With Faster, the merge commit carries a
// `Work-Pace: fast` trailer and the workflow passes mode `all`: every open PR is updated at once.
//
// For the PR chosen (a claude/* branch of this repo):
// 1. Stacked on a branch whose own PR was merged: retarget it to the default branch.
//    (Still stacked on an open PR: left alone; it is updated once its base is merged.)
// 2. Behind the default branch: update it (PUT update-branch, pinned to the head it saw).
//    A merge conflict labels it needs-rebase and comments once; Claude's next board run resolves it.
//    So does a refused push of workflow files: GITHUB_TOKEN may not change .github/workflows, so
//    when main changed them, only Claude's own rebase can bring them in.
//    An up-to-date PR loses a stale needs-rebase label (Claude rebased it), and nothing more is
//    needed this run: its CI already ran on an up-to-date head.
// 3. Then dispatch the build workflow on its branch: pushes made with GITHUB_TOKEN start no
//    workflows, so without this the new head would have no CI run and never become mergeable.
//    A ready PR gets the full run (emulator tests, preview release); a draft (only mode `all`
//    updates drafts) just the quick `build` job (input full=false, #52).
// 4. Then delete the PR's `pull_request` run GitHub holds for approval on the new head (#64,
//    held-runs.js): GitHub registers it because of the update but never runs it (it came from
//    GITHUB_TOKEN), and the dispatched build tests the same commit. So each update leaves exactly
//    one real CI run, and no "approval required" run sits on the PR.
// Merge all (#57) updates its current PR itself; if this run updated the same PR first, Merge all
// sees the moved head and waits for its checks, so the two never undo each other.
// Nothing here pushes to the default branch, so this workflow can't trigger itself.
'use strict';

// #84: this file is only the per-run orchestration. The GitHub calls are update-prs-github.js,
// the steps for one PR update-prs-one.js; each is required relative to this file, so the
// workflow's sparse checkout of .github/scripts brings them all.

const { selectPrsToUpdate, MODES } = require('./update-prs-select.js');
const gh = require('./update-prs-github.js');
const { updateOne } = require('./update-prs-one.js');

const PREFIX = 'claude/';

/** Results that mean the PR needs nothing more this run (a conflict or an error does not: the next PR is tried). */
const SETTLED = new Set(['updated', 'update-pending', 'up-to-date', 'retargeted']);

/** Claude's open PRs: claude/* branches of this repo (not forks). */
async function listOurPrs({ github, owner, repo }) {
  const open = await github.paginate(github.rest.pulls.list, { owner, repo, state: 'open', per_page: 100 });
  return open.filter((p) => p.head.ref.startsWith(PREFIX) && p.head.repo && p.head.repo.full_name === `${owner}/${repo}`);
}

/** The base branches (other than main) of [prs] whose own PR was merged; one that can't be told counts as open. */
async function findMergedBases({ main, log }, prs, baseWasMerged) {
  const mergedBases = new Set();
  for (const pr of prs) {
    if (pr.base.ref === main || mergedBases.has(pr.base.ref)) continue;
    try {
      if (await baseWasMerged(pr.base.ref)) mergedBases.add(pr.base.ref);
    } catch (error) {
      log(`#${pr.number}: couldn't tell whether ${pr.base.ref} was merged: ${error.message}`); // treated as stacked
    }
  }
  return mergedBases;
}

async function attempt(ctx, pr, deps) {
  try {
    return await updateOne(ctx, pr, deps);
  } catch (error) {
    ctx.log(`#${pr.number}: failed: ${error.status || ''} ${error.message}`);
    return { number: pr.number, action: 'error', error: String(error.message) };
  }
}

// Every PR still stacked on a merged branch is retargeted now, even one not updated this run (a
// draft, or queued behind the next PR): otherwise it would stay stacked, which Merge all and auto
// merge never take, until some later merge happened to pick it.
async function retargetWaiting(ctx, prs, mergedBases, byNumber) {
  for (const pr of prs) {
    if (pr.base.ref === ctx.main || !mergedBases.has(pr.base.ref)) continue;
    try {
      await gh.retarget(ctx, pr);
    } catch (error) {
      ctx.log(`#${pr.number}: failed to retarget: ${error.status || ''} ${error.message}`);
      byNumber.set(pr.number, { number: pr.number, action: 'error', error: String(error.message) });
    }
  }
}

async function updatePrs({
  github,
  owner,
  repo,
  main,
  workflow = 'build.yml',
  log = console.log,
  wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  headPolls = 12,
  heldPolls = 3,
  pollMs = 5000,
  mode = MODES.NEXT,
}) {
  const ctx = { github, owner, repo, main, workflow, log, wait, headPolls, heldPolls, pollMs };
  const baseWasMerged = gh.mergedBaseChecker(ctx);
  const ours = await listOurPrs(ctx);
  const mergedBases = await findMergedBases(ctx, ours, baseWasMerged);
  const { queue, skipped, limit } = selectPrsToUpdate(ours, { main, mergedBases, mode });
  const byNumber = new Map(skipped.map(({ pr, action }) => [pr.number, { number: pr.number, action }]));
  const waiting = []; // not updated this run
  let settled = 0;
  for (const pr of queue) {
    if (settled >= limit) {
      byNumber.set(pr.number, { number: pr.number, action: 'queued' });
      waiting.push(pr);
      continue;
    }
    const result = await attempt(ctx, pr, { baseWasMerged });
    if (SETTLED.has(result.action)) settled += 1;
    byNumber.set(pr.number, result);
  }
  const drafts = skipped.filter((s) => s.action === 'draft').map((s) => s.pr);
  await retargetWaiting(ctx, [...waiting, ...drafts], mergedBases, byNumber);
  if (queue.length) log(`next in merge order: ${queue.map((p) => `#${p.number}`).join(', ')} (updating up to ${limit})`);
  return ours.map((pr) => byNumber.get(pr.number));
}

// The error checks and label name are re-exported for existing callers and tests.
const { isConflict, isWorkflowRefusal, isUnknownInput, LABEL } = gh;
module.exports = { updatePrs, isConflict, isWorkflowRefusal, isUnknownInput, LABEL };
