// Brings one PR up to date with the default branch (#84, split out of update-prs.js; the steps
// are described at the top of that file). Built from the calls in update-prs-github.js.
'use strict';

const { dropHeldRuns } = require('./held-runs.js');
const gh = require('./update-prs-github.js');

/**
 * Updates [pr] and re-runs its build. [ctx] is `{ github, owner, repo, main, log, workflow, wait,
 * headPolls, heldPolls, pollMs }`; [baseWasMerged] answers whether a base branch was merged
 * (shared with the caller, so each base is asked about once per run).
 * Resolves to `{ number, action }`: stacked, retargeted, up-to-date, conflict, updated or
 * update-pending. Errors other than a conflict or workflow refusal are thrown.
 */
async function updateOne(ctx, pr, { baseWasMerged }) {
  const { main, log, workflow } = ctx;
  const n = pr.number;
  const labels = (pr.labels || []).map((l) => l.name);
  let retargeted = false;
  if (pr.base.ref !== main) {
    if (!(await baseWasMerged(pr.base.ref))) return { number: n, action: 'stacked' };
    await gh.retarget(ctx, pr);
    retargeted = true;
  }
  if ((await gh.behindBy(ctx, pr.head.sha)) === 0) {
    await gh.clearLabel(ctx, n, labels);
    return { number: n, action: retargeted ? 'retargeted' : 'up-to-date' };
  }
  const why = await gh.updateBranch(ctx, pr);
  if (why) {
    await gh.markNeedsRebase(ctx, pr, labels, why);
    log(`#${n}: ${why}; labelled ${gh.LABEL}`);
    return { number: n, action: 'conflict' };
  }
  const head = await gh.waitForNewHead(ctx, n, pr.head.sha);
  await gh.clearLabel(ctx, n, labels);
  const full = !pr.draft;
  await gh.dispatch(ctx, pr.head.ref, full);
  const { github, owner, repo, wait, heldPolls, pollMs } = ctx;
  const dropped = head ? await dropHeldRuns({ github, owner, repo, workflow, sha: head, wait, polls: heldPolls, pollMs, log }) : 0;
  log(`#${n}: updated with ${main}${head ? ` (now ${head.slice(0, 7)})` : ' (head not moved yet)'}; ${workflow} dispatched (${full ? 'full' : 'build only, draft'})` +
    (dropped ? `; deleted ${dropped} held pull_request run(s)` : ''));
  return { number: n, action: head ? 'updated' : 'update-pending' };
}

module.exports = { updateOne };
