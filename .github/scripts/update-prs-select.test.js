// Tests for update-prs-select.js (#79). Run: node --test .github/scripts/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { selectPrsToUpdate, modeForCommit, MODES } = require('./update-prs-select.js');

function pr(number, { base = 'main', draft = false } = {}) {
  return { number, draft, base: { ref: base } };
}

const numbers = (list) => list.map((p) => p.number);
const select = (prs, opts = {}) => selectPrsToUpdate(prs, { main: 'main', ...opts });

test('next mode: the oldest PR first, one update per run', () => {
  const { queue, skipped, limit } = select([pr(24), pr(22), pr(23)]);
  assert.deepStrictEqual(numbers(queue), [22, 23, 24]);
  assert.deepStrictEqual(skipped, []);
  assert.strictEqual(limit, 1);
});

test('the default mode is next', () => {
  assert.strictEqual(selectPrsToUpdate([pr(1)], { main: 'main' }).limit, 1);
  assert.strictEqual(MODES.NEXT, 'next');
});

test('a PR stacked on the branch just merged goes first, as Merge all merges a stack right after its base', () => {
  const { queue } = select([pr(20), pr(25, { base: 'claude/21-x' })], { mergedBases: new Set(['claude/21-x']) });
  assert.deepStrictEqual(numbers(queue), [25, 20]);
});

test('a PR stacked on an open PR is skipped', () => {
  const { queue, skipped } = select([pr(22), pr(23, { base: 'claude/22-x' })]);
  assert.deepStrictEqual(numbers(queue), [22]);
  assert.deepStrictEqual(skipped.map((s) => [s.pr.number, s.action]), [[23, 'stacked']]);
});

test('drafts are skipped: they cannot be merged yet', () => {
  const { queue, skipped } = select([pr(22, { draft: true }), pr(23)]);
  assert.deepStrictEqual(numbers(queue), [23]);
  assert.deepStrictEqual(skipped.map((s) => [s.pr.number, s.action]), [[22, 'draft']]);
});

test('a draft stacked on an open PR counts as stacked', () => {
  const { skipped } = select([pr(23, { base: 'claude/22-x', draft: true })]);
  assert.deepStrictEqual(skipped.map((s) => s.action), ['stacked']);
});

test('a PR without a draft field counts as ready', () => {
  const p = pr(22); delete p.draft;
  assert.deepStrictEqual(numbers(select([p]).queue), [22]);
});

test('no PRs: empty queue', () => {
  assert.deepStrictEqual(select([]), { queue: [], skipped: [], limit: 1 });
});

test('an unknown mode is refused, not silently treated as next', () => {
  assert.throws(() => select([pr(1)], { mode: 'fast' }), /unknown update mode/);
  assert.throws(() => select([pr(1)], { mode: 'constructor' }), /unknown update mode/);
});

test('the input list is not reordered', () => {
  const prs = [pr(24), pr(22)];
  select(prs);
  assert.deepStrictEqual(numbers(prs), [24, 22]);
});

// --- #83, work pace ---

test('all mode: every eligible PR at once, in the same order, drafts included', () => {
  const prs = [pr(24), pr(22, { draft: true }), pr(25, { base: 'claude/21-x' }), pr(23, { base: 'claude/20-x' })];
  const { queue, skipped, limit } = select(prs, { mode: MODES.ALL, mergedBases: new Set(['claude/21-x']) });
  assert.deepStrictEqual(numbers(queue), [25, 22, 24]);
  assert.deepStrictEqual(skipped.map((s) => [s.pr.number, s.action]), [[23, 'stacked']]);
  assert.strictEqual(limit, Infinity);
});

test('next mode with the same PRs: one at a time, drafts skipped', () => {
  const prs = [pr(24), pr(22, { draft: true }), pr(25, { base: 'claude/21-x' })];
  const { queue, skipped, limit } = select(prs, { mode: MODES.NEXT, mergedBases: new Set(['claude/21-x']) });
  assert.deepStrictEqual(numbers(queue), [25, 24]);
  assert.deepStrictEqual(skipped.map((s) => s.pr.number), [22]);
  assert.strictEqual(limit, 1);
});

test('all mode with no PRs: empty queue', () => {
  assert.deepStrictEqual(select([], { mode: MODES.ALL }), { queue: [], skipped: [], limit: Infinity });
});

const SUBJECT = 'Merge pull request #5 from VeganMarshmallow/claude/5-y';

test('modeForCommit: a fast trailer means all', () => {
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nPR title\n\nWork-Pace: fast`), MODES.ALL);
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nPR title\n\nwork-pace:FAST  `), MODES.ALL);
  assert.strictEqual(modeForCommit(`${SUBJECT}\r\n\r\nPR title\r\n\r\n  Work-Pace : Fast\r\n`), MODES.ALL);
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nPR title\n\nSigned-off-by: x\nWork-Pace: fast\n`), MODES.ALL);
});

test('modeForCommit: anything else means next', () => {
  for (const message of [undefined, null, '', 42, {}, SUBJECT, 'Work-Pace: save', 'Work-Pace: turbo', 'Work-Pace:',
    `${SUBJECT}\n\nPR title\n\nWork-Pace: save`, `${SUBJECT}\n\nPR title\n\nWork-Pace: turbo`, `${SUBJECT}\n\nPR title\n\nWork-Pace:`,
    `${SUBJECT}\n\nPR title\n\nTitle mentions Work-Pace: fast inline`, `${SUBJECT}\n\nPR title\n\nWork-Pace: fast please`,
    `${SUBJECT}\n\nPR title\n\nX-Work-Pace: fast`]) {
    assert.strictEqual(modeForCommit(message), MODES.NEXT, String(message));
  }
});

test('modeForCommit: GitHub\'s own two-paragraph message never switches, whatever the PR title says', () => {
  // A merge made on github.com (or by the widget in Save usage): subject + PR title, no trailer.
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nWork-Pace: fast`), MODES.NEXT);
  assert.strictEqual(modeForCommit('Work-Pace: fast'), MODES.NEXT);
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\n\n\nWork-Pace: fast`), MODES.NEXT);
});

test('modeForCommit: only the last paragraph is the trailer block', () => {
  // A quoted trailer in the middle of a merge message (title + description on github.com) doesn't count.
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nPR title\n\nWork-Pace: fast\n\nMore description.`), MODES.NEXT);
  // The widget's real trailer comes after a PR titled with another value.
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nWork-Pace: save\n\nWork-Pace: fast`), MODES.ALL);
});

test('modeForCommit: the last trailer wins', () => {
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nT\n\nWork-Pace: fast\nWork-Pace: save`), MODES.NEXT);
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\nT\n\nWork-Pace: save\nWork-Pace: fast`), MODES.ALL);
});

test('modeForCommit: a huge message is handled', () => {
  const body = 'x'.repeat(200000);
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\n${body}\n\nWork-Pace: fast`), MODES.ALL);
  assert.strictEqual(modeForCommit(`${SUBJECT}\n\n${body}`), MODES.NEXT);
});

test('modeForCommit output is always a mode the selector accepts', () => {
  for (const m of [`${SUBJECT}\n\nT\n\nWork-Pace: fast`, 'Work-Pace: save', '']) assert.doesNotThrow(() => select([pr(1)], { mode: modeForCommit(m) }));
});
