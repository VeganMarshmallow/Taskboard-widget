// The GitHub calls update-prs.js is built from (#84), as plain functions. Each takes a context
// `{ github, owner, repo, main, log, ... }` first (the timing fields only where a call polls), so
// each can be tested alone against a small fake Octokit. No orchestration lives here: deciding
// which PR to touch, and in what order, is update-prs-one.js and update-prs.js.
'use strict';

const LABEL = 'needs-rebase';

function messageOf(error) {
  return String((error && error.response && error.response.data && error.response.data.message) || (error && error.message) || '');
}

/** update-branch's 422 for a merge conflict (other 422s, e.g. a moved head, are plain errors). */
function isConflict(error) {
  return Boolean(error) && error.status === 422 && /conflict/i.test(messageOf(error));
}

/** A dispatch refused because the branch's build.yml predates the `full` input (#52). */
function isUnknownInput(error) {
  return Boolean(error) && error.status === 422 && /unexpected inputs/i.test(messageOf(error));
}

/** GitHub refusing a GITHUB_TOKEN push that would change workflow files (needs the `workflows` scope). */
function isWorkflowRefusal(error) {
  return Boolean(error) && (error.status === 403 || error.status === 422) && /workflow/i.test(messageOf(error));
}

/** Whether [base] had a PR of its own that was merged (a closed-unmerged one does not count). */
async function wasBaseMerged({ github, owner, repo }, base) {
  const closed = await github.paginate(github.rest.pulls.list, { owner, repo, state: 'closed', head: `${owner}:${base}`, per_page: 100 });
  return closed.some((p) => Boolean(p.merged_at));
}

/** [wasBaseMerged], asked of GitHub once per base branch for the life of the returned function. */
function mergedBaseChecker(ctx) {
  const merged = new Map();
  return async (base) => {
    if (!merged.has(base)) merged.set(base, await wasBaseMerged(ctx, base));
    return merged.get(base);
  };
}

/** Moves a PR whose base branch's own PR was merged onto the default branch. No CI is started. */
async function retarget({ github, owner, repo, main, log }, pr) {
  await github.rest.pulls.update({ owner, repo, pull_number: pr.number, base: main });
  log(`#${pr.number}: retargeted from ${pr.base.ref} to ${main}`);
}

/** How many commits of the default branch [sha] lacks. */
async function behindBy({ github, owner, repo, main }, sha) {
  const { data } = await github.rest.repos.compareCommitsWithBasehead({ owner, repo, basehead: `${main}...${sha}` });
  return data.behind_by;
}

/**
 * Updates [pr]'s branch with the default branch, pinned to the head it saw. Returns null when
 * GitHub took the update, or why it refused one only Claude can fix (a conflict, or workflow
 * files the token may not push). Any other error is thrown.
 */
async function updateBranch({ github, owner, repo, main }, pr) {
  try {
    await github.rest.pulls.updateBranch({ owner, repo, pull_number: pr.number, expected_head_sha: pr.head.sha });
    return null;
  } catch (error) {
    if (isConflict(error)) return 'merge conflict';
    if (isWorkflowRefusal(error)) return `\`${main}\` changed workflow files, which this workflow's token may not push`;
    throw error;
  }
}

/** Polls the PR until its head is no longer [oldSha]; the new SHA, or null after [headPolls] polls. */
async function waitForNewHead({ github, owner, repo, wait, headPolls, pollMs }, number, oldSha) {
  for (let i = 0; i < headPolls; i++) {
    await wait(pollMs);
    const { data } = await github.rest.pulls.get({ owner, repo, pull_number: number });
    if (data.head.sha !== oldSha) return data.head.sha;
  }
  return null;
}

/** Labels the PR needs-rebase and says [why], unless it already carries the label (comments once). */
async function markNeedsRebase({ github, owner, repo, main }, pr, labels, why) {
  if (labels.includes(LABEL)) return;
  await github.rest.issues.addLabels({ owner, repo, issue_number: pr.number, labels: [LABEL] });
  await github.rest.issues.createComment({
    owner, repo, issue_number: pr.number,
    body: `🤖 Couldn't update this branch with \`${main}\`: ${why}. Labelled \`${LABEL}\`; Claude rebases it on its next board run.`,
  });
}

/** Drops a stale needs-rebase label; a failure to remove it is ignored. */
async function clearLabel({ github, owner, repo }, number, labels) {
  if (labels.includes(LABEL)) {
    await github.rest.issues.removeLabel({ owner, repo, issue_number: number, name: LABEL }).catch(() => {});
  }
}

// A branch whose head hasn't moved yet may still carry a build.yml without the `full` input:
// GitHub refuses unknown inputs, so dispatch it plainly (that file runs everything anyway).
async function dispatch({ github, owner, repo, workflow }, ref, full) {
  const args = { owner, repo, workflow_id: workflow, ref };
  try {
    await github.rest.actions.createWorkflowDispatch({ ...args, inputs: { full: String(full) } });
  } catch (error) {
    if (!isUnknownInput(error)) throw error;
    await github.rest.actions.createWorkflowDispatch(args);
  }
}

module.exports = {
  LABEL, isConflict, isUnknownInput, isWorkflowRefusal,
  wasBaseMerged, mergedBaseChecker, retarget, behindBy, updateBranch, waitForNewHead,
  markNeedsRebase, clearLabel, dispatch,
};
