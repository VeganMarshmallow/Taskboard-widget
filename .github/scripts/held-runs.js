// Drops the empty build run GitHub holds for approval after update-prs.js updates a PR (#64).
//
// Why it exists: update-prs.js updates a PR branch with GITHUB_TOKEN (PUT update-branch). GitHub
// still registers the PR's `pull_request` (synchronize) run for that new head, but because
// github-actions[bot] caused it, it holds the whole run at the approval gate: status `completed`,
// conclusion `action_required`, zero jobs, and the PR title as its name (run-name is never evaluated).
// The gate is run-level, so a job `if:` on the actor can't skip it. A real run from the update
// would need a PAT or App token, i.e. a stored secret. The build update-prs.js dispatches already
// tests that exact commit, so the held run is a stuck duplicate: we delete it, leaving one real run.
//
// Only runs that are exactly that are touched: the given workflow, event `pull_request`, conclusion
// `action_required`, on the head update-prs.js just made, started by github-actions[bot]. A held run
// of a real push (a person's or Claude's) is never deleted. The widget's Merge check also ignores a
// held run once the same workflow passed on the commit (core/merge/WorkflowRuns.kt), so if this step
// fails or GitHub registers the run late, merging still works.
'use strict';

const BOT = 'github-actions[bot]';

/** Whether [run] is the held duplicate of an update-prs.js update to [sha]. */
function isHeldUpdateRun(run, sha) {
  if (!run || run.head_sha !== sha) return false;
  const login = (run.triggering_actor && run.triggering_actor.login) || (run.actor && run.actor.login);
  return run.event === 'pull_request' && run.conclusion === 'action_required' && login === BOT;
}

/**
 * Looks up to [polls] times (GitHub registers the run within seconds of the update) and deletes the
 * held runs of [workflow] on [sha]. Returns how many it deleted. Never throws: a failure is logged,
 * and a failed delete doesn't stop the remaining ones.
 */
async function dropHeldRuns({ github, owner, repo, workflow, sha, wait, polls = 3, pollMs = 5000, log = console.log }) {
  try {
    for (let i = 0; i < polls; i++) {
      if (i > 0) await wait(pollMs);
      const { data } = await github.rest.actions.listWorkflowRuns({
        owner, repo, workflow_id: workflow, head_sha: sha, event: 'pull_request', status: 'action_required', per_page: 20,
      });
      const held = (data.workflow_runs || []).filter((run) => isHeldUpdateRun(run, sha));
      if (held.length === 0) continue;
      let deleted = 0;
      for (const run of held) {
        try {
          await github.rest.actions.deleteWorkflowRun({ owner, repo, run_id: run.id });
          deleted++;
        } catch (error) {
          logFailure(log, `run ${run.id}`, sha, error); // one failed delete doesn't stop the others
        }
      }
      return deleted;
    }
  } catch (error) {
    logFailure(log, 'the held run', sha, error);
  }
  return 0;
}

function logFailure(log, what, sha, error) {
  log(`couldn't drop ${what} on ${sha.slice(0, 7)}: ${error.status || ''} ${error.message}`);
}

module.exports = { dropHeldRuns, isHeldUpdateRun, BOT };
