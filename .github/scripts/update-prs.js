// Keeps Claude's open PRs up to date after every merge to the default branch (#45). Run by
// .github/workflows/update-prs.yml through actions/github-script, which passes an Octokit client.
//
// For each open PR from a claude/* branch of this repo:
// 1. Stacked on a branch whose own PR was merged: retarget it to the default branch.
//    (Still stacked on an open PR: left alone; it is updated once its base is merged.)
// 2. Behind the default branch: update it (PUT update-branch, pinned to the head it saw).
//    A merge conflict labels it needs-rebase and comments once; Claude's next board run resolves it.
//    So does a refused push of workflow files: GITHUB_TOKEN may not change .github/workflows, so
//    when main changed them, only Claude's own rebase can bring them in.
//    An up-to-date PR loses a stale needs-rebase label (Claude rebased it).
// 3. Then dispatch the build workflow on its branch: pushes made with GITHUB_TOKEN start no
//    workflows, so without this the new head would have no CI run and never become mergeable.
//    A ready PR gets the full run (emulator tests, preview release); a draft only the quick
//    `build` job (input full=false, #52): drafts can't be merged, and its ready_for_review event
//    starts the full run later.
// 4. Then delete the PR's `pull_request` run GitHub holds for approval on the new head (#64,
//    held-runs.js): GitHub registers it because of the update but never runs it (it came from
//    GITHUB_TOKEN), and the dispatched build tests the same commit. So each update leaves exactly
//    one real CI run, and no "approval required" run sits on the PR.
// Nothing here pushes to the default branch, so this workflow can't trigger itself.
'use strict';

const { dropHeldRuns } = require('./held-runs.js');

const PREFIX = 'claude/';
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
}) {
  const fullName = `${owner}/${repo}`;
  const merged = new Map();

  async function baseWasMerged(base) {
    if (!merged.has(base)) {
      const closed = await github.paginate(github.rest.pulls.list, { owner, repo, state: 'closed', head: `${owner}:${base}`, per_page: 100 });
      merged.set(base, closed.some((p) => Boolean(p.merged_at)));
    }
    return merged.get(base);
  }

  async function waitForNewHead(number, oldSha) {
    for (let i = 0; i < headPolls; i++) {
      await wait(pollMs);
      const { data } = await github.rest.pulls.get({ owner, repo, pull_number: number });
      if (data.head.sha !== oldSha) return data.head.sha;
    }
    return null;
  }

  async function markNeedsRebase(pr, labels, why) {
    if (labels.includes(LABEL)) return;
    await github.rest.issues.addLabels({ owner, repo, issue_number: pr.number, labels: [LABEL] });
    await github.rest.issues.createComment({
      owner, repo, issue_number: pr.number,
      body: `🤖 Couldn't update this branch with \`${main}\`: ${why}. Labelled \`${LABEL}\`; Claude rebases it on its next board run.`,
    });
  }

  async function clearLabel(n, labels) {
    if (labels.includes(LABEL)) {
      await github.rest.issues.removeLabel({ owner, repo, issue_number: n, name: LABEL }).catch(() => {});
    }
  }

  // A branch whose head hasn't moved yet may still carry a build.yml without the `full` input:
  // GitHub refuses unknown inputs, so dispatch it plainly (that file runs everything anyway).
  async function dispatch(ref, full) {
    const args = { owner, repo, workflow_id: workflow, ref };
    try {
      await github.rest.actions.createWorkflowDispatch({ ...args, inputs: { full: String(full) } });
    } catch (error) {
      if (!isUnknownInput(error)) throw error;
      await github.rest.actions.createWorkflowDispatch(args);
    }
  }

  async function updateOne(pr) {
    const n = pr.number;
    const labels = (pr.labels || []).map((l) => l.name);
    let retargeted = false;
    if (pr.base.ref !== main) {
      if (!(await baseWasMerged(pr.base.ref))) return { number: n, action: 'stacked' };
      await github.rest.pulls.update({ owner, repo, pull_number: n, base: main });
      retargeted = true;
      log(`#${n}: retargeted from ${pr.base.ref} to ${main}`);
    }
    const { data: diff } = await github.rest.repos.compareCommitsWithBasehead({ owner, repo, basehead: `${main}...${pr.head.sha}` });
    if (diff.behind_by === 0) {
      await clearLabel(n, labels);
      return { number: n, action: retargeted ? 'retargeted' : 'up-to-date' };
    }
    try {
      await github.rest.pulls.updateBranch({ owner, repo, pull_number: n, expected_head_sha: pr.head.sha });
    } catch (error) {
      const why = isConflict(error) ? 'merge conflict' : isWorkflowRefusal(error) ? `\`${main}\` changed workflow files, which this workflow's token may not push` : null;
      if (!why) throw error;
      await markNeedsRebase(pr, labels, why);
      log(`#${n}: ${why}; labelled ${LABEL}`);
      return { number: n, action: 'conflict' };
    }
    const head = await waitForNewHead(n, pr.head.sha);
    await clearLabel(n, labels);
    const full = !pr.draft;
    await dispatch(pr.head.ref, full);
    const dropped = head ? await dropHeldRuns({ github, owner, repo, workflow, sha: head, wait, polls: heldPolls, pollMs, log }) : 0;
    log(`#${n}: updated with ${main}${head ? ` (now ${head.slice(0, 7)})` : ' (head not moved yet)'}; ${workflow} dispatched (${full ? 'full' : 'build only, draft'})` +
      (dropped ? `; deleted ${dropped} held pull_request run(s)` : ''));
    return { number: n, action: head ? 'updated' : 'update-pending' };
  }

  const open = await github.paginate(github.rest.pulls.list, { owner, repo, state: 'open', per_page: 100 });
  const ours = open.filter((p) => p.head.ref.startsWith(PREFIX) && p.head.repo && p.head.repo.full_name === fullName);
  const results = [];
  for (const pr of ours) {
    try {
      results.push(await updateOne(pr));
    } catch (error) {
      log(`#${pr.number}: failed: ${error.status || ''} ${error.message}`);
      results.push({ number: pr.number, action: 'error', error: String(error.message) });
    }
  }
  return results;
}

module.exports = { updatePrs, isConflict, isWorkflowRefusal, isUnknownInput, LABEL };
