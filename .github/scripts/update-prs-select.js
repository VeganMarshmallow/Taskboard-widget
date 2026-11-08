// Which of Claude's open PRs the update-prs workflow brings up to date after a merge (#79), and in
// what order. Kept apart from update-prs.js (which does the GitHub calls) so the choice is tested on
// its own, and so a later setting (#83, work pace) can add modes here without touching the calls.
//
// Mode `next` (the only one today): only the next PR in merge order is updated and re-tested, not
// every open PR, so CI runs once per merge instead of once per open PR. When that PR merges, the
// next merge's run updates the one after it. The order follows the widget's Merge all order where
// a workflow can tell: a PR stacked on the branch just merged comes first (Merge all merges a stack
// bottom-up, right after its base), then the oldest PR (lowest number). Drafts can't be merged and
// a PR stacked on a still-open PR waits for its base, so neither is updated.
'use strict';

const MODES = Object.freeze({ NEXT: 'next' });

/** How many PRs each mode brings up to date per run (a PR that conflicts doesn't count: the next one is tried). */
const UPDATES_PER_RUN = Object.freeze({ [MODES.NEXT]: 1 });

/**
 * Splits [prs] (Claude's open PRs, as GitHub lists them) into the queue to try in order and the ones
 * skipped this run, each with why. [mergedBases]: the base branches whose own PR was merged.
 */
function selectPrsToUpdate(prs, { main, mergedBases = new Set(), mode = MODES.NEXT } = {}) {
  if (!(mode in UPDATES_PER_RUN)) throw new Error(`unknown update mode: ${mode}`);
  const skipped = [];
  const queue = [];
  for (const pr of prs) {
    const onMain = pr.base.ref === main;
    if (!onMain && !mergedBases.has(pr.base.ref)) skipped.push({ pr, action: 'stacked' });
    else if (pr.draft) skipped.push({ pr, action: 'draft' });
    else queue.push(pr);
  }
  const retarget = (pr) => (pr.base.ref === main ? 1 : 0);
  queue.sort((a, b) => retarget(a) - retarget(b) || a.number - b.number);
  return { queue, skipped, limit: UPDATES_PER_RUN[mode] };
}

module.exports = { selectPrsToUpdate, MODES, UPDATES_PER_RUN };
