// Which of Claude's open PRs the update-prs workflow brings up to date after a merge (#79), and in
// what order. Kept apart from update-prs.js (which does the GitHub calls) so the choice is tested on
// its own, and so a later setting (#83, work pace) can add modes here without touching the calls.
//
// Mode `next` (Save usage, the default): only the next PR in merge order is updated and re-tested, not
// every open PR, so CI runs once per merge instead of once per open PR. When that PR merges, the
// next merge's run updates the one after it. The order follows the widget's Merge all order where
// a workflow can tell: a PR stacked on the branch just merged comes first (Merge all merges a stack
// bottom-up, right after its base), then the oldest PR (lowest number). Drafts can't be merged and
// a PR stacked on a still-open PR waits for its base, so neither is updated.
//
// Mode `all` (#83, work pace Faster): every eligible open PR is updated at once, drafts included (a
// draft gets only the quick build), as before #79. Same order; a PR stacked on an open PR still waits.
//
// Which mode a run uses comes from the merge commit that started it (modeForCommit): the widget adds a
// `Work-Pace: fast` trailer to the merges it makes while the owner's Work pace setting is Faster. The
// setting itself lives in the private board repo, which this workflow's GITHUB_TOKEN can't read.
'use strict';

const MODES = Object.freeze({ NEXT: 'next', ALL: 'all' });

/** How many PRs each mode brings up to date per run (a PR that conflicts doesn't count: the next one is tried). */
const UPDATES_PER_RUN = Object.freeze({ [MODES.NEXT]: 1, [MODES.ALL]: Infinity });

/** Modes that also update drafts (with the quick build only). */
const UPDATES_DRAFTS = Object.freeze({ [MODES.NEXT]: false, [MODES.ALL]: true });

const TRAILER = /^[ \t]*work-pace[ \t]*:[ \t]*(\S*)[ \t]*$/gim;

/**
 * The mode for a run started by a push whose head commit has [message]: `all` when its last
 * `Work-Pace:` trailer line says `fast` (any case), else `next` (no trailer, `save`, unknown, no message).
 */
function modeForCommit(message) {
  if (typeof message !== 'string') return MODES.NEXT;
  const values = [...message.matchAll(TRAILER)].map((m) => m[1].toLowerCase());
  return values.length && values[values.length - 1] === 'fast' ? MODES.ALL : MODES.NEXT;
}

/**
 * Splits [prs] (Claude's open PRs, as GitHub lists them) into the queue to try in order and the ones
 * skipped this run, each with why. [mergedBases]: the base branches whose own PR was merged.
 */
function selectPrsToUpdate(prs, { main, mergedBases = new Set(), mode = MODES.NEXT } = {}) {
  if (!Object.prototype.hasOwnProperty.call(UPDATES_PER_RUN, mode)) throw new Error(`unknown update mode: ${mode}`);
  const skipped = [];
  const queue = [];
  for (const pr of prs) {
    const onMain = pr.base.ref === main;
    if (!onMain && !mergedBases.has(pr.base.ref)) skipped.push({ pr, action: 'stacked' });
    else if (pr.draft && !UPDATES_DRAFTS[mode]) skipped.push({ pr, action: 'draft' });
    else queue.push(pr);
  }
  const retarget = (pr) => (pr.base.ref === main ? 1 : 0);
  queue.sort((a, b) => retarget(a) - retarget(b) || a.number - b.number);
  return { queue, skipped, limit: UPDATES_PER_RUN[mode] };
}

module.exports = { selectPrsToUpdate, modeForCommit, MODES, UPDATES_PER_RUN };
