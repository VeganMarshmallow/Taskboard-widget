// Tests for update-prs-select.js (#79). Run: node --test .github/scripts/*.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { selectPrsToUpdate, MODES } = require('./update-prs-select.js');

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
  assert.throws(() => select([pr(1)], { mode: 'all' }), /unknown update mode/);
});

test('the input list is not reordered', () => {
  const prs = [pr(24), pr(22)];
  select(prs);
  assert.deepStrictEqual(numbers(prs), [24, 22]);
});
