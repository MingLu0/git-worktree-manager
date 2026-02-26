#!/usr/bin/env node
/**
 * Generate JetBrains Marketplace changeNotes HTML for build.gradle.kts.
 *
 * Goal: user-friendly, short release notes (not a raw changelog).
 *
 * How it works:
 * - Finds merged PRs since the latest tag (or a provided --sinceTag)
 * - Fetches PR titles via `gh api`
 * - Classifies into What's new vs Fixes using simple heuristics
 * - Limits bullets to keep it readable
 * - Optionally updates build.gradle.kts in-place (--apply)
 *
 * Requirements:
 * - `gh` authenticated
 * - repo remote 'origin' points to GitHub
 *
 * Usage:
 *   node scripts/generate-change-notes.mjs
 *   node scripts/generate-change-notes.mjs --sinceTag v1.1.8
 *   node scripts/generate-change-notes.mjs --apply
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

function sh(cmd, args, opts = {}) {
  return execFileSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim();
}

function json(cmd, args) {
  return JSON.parse(sh(cmd, args));
}

function latestTag() {
  try {
    return sh('git', ['describe', '--tags', '--abbrev=0']);
  } catch {
    return null;
  }
}

function prNumbersSinceTag(tag) {
  // Capture both merge commits and squash merges.
  // - Merge commit subject: "Merge pull request #123 from ..."
  // - Squash merge subject (GitHub default): "Some title (#123)"
  const range = tag ? `${tag}..HEAD` : 'HEAD';
  const log = sh('git', ['log', '--pretty=%s', range]);
  const nums = [];

  for (const line of log.split('\n')) {
    const merge = line.match(/Merge pull request #(\d+)\b/);
    if (merge) {
      nums.push(Number(merge[1]));
      continue;
    }

    const squash = line.match(/\(#(\d+)\)\s*$/);
    if (squash) nums.push(Number(squash[1]));
  }

  // Newest first so we prefer recent changes in the limited bullets.
  return Array.from(new Set(nums)).sort((a, b) => b - a);
}

function repoSlug() {
  // infer from origin url
  const url = sh('git', ['remote', 'get-url', 'origin']);
  // git@github.com:Owner/Repo.git OR https://github.com/Owner/Repo.git
  const m = url.match(/github\.com[:/](.+?)\/?(\.git)?$/);
  if (!m) throw new Error(`Couldn't parse origin remote as a GitHub repo: ${url}`);
  return m[1].replace(/\.git$/, '');
}

function fetchPrTitle(repo, number) {
  const res = json('gh', ['api', `repos/${repo}/pulls/${number}`, '--jq', '{title:.title}']);
  return res.title;
}

function classify(title) {
  const t = title.toLowerCase();
  const isFix =
    t.startsWith('fix') ||
    t.includes('bug') ||
    t.includes('crash') ||
    t.includes('stability') ||
    t.includes('error') ||
    t.includes('race') ||
    t.includes('deadlock') ||
    t.includes('assertion') ||
    t.includes('regression');

  const isReleaseChore = t.startsWith('release') || t.includes('bump version');

  return {
    skip: isReleaseChore,
    section: isFix ? 'fixes' : 'whatsNew',
  };
}

function toBullet(title) {
  // Remove conventional prefixes and keep it short.
  return title
    .replace(/^\s*(feat|fix|chore|refactor|docs|test)(\([^)]*\))?:\s*/i, '')
    .replace(/^\s*fix:\s*/i, '')
    .trim();
}

function renderHtml({ whatsNew, fixes }) {
  const parts = [];

  if (whatsNew.length) {
    parts.push('<b>What\'s new</b>');
    parts.push('<ul>');
    for (const b of whatsNew) parts.push(`  <li>${escapeHtml(b)}</li>`);
    parts.push('</ul>');
  }

  if (fixes.length) {
    parts.push('<b>Fixes</b>');
    parts.push('<ul>');
    for (const b of fixes) parts.push(`  <li>${escapeHtml(b)}</li>`);
    parts.push('</ul>');
  }

  if (!parts.length) {
    return '<b>Fixes</b>\n<ul>\n  <li>Various stability improvements.</li>\n</ul>';
  }

  return parts.join('\n');
}

function escapeHtml(s) {
  return s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function updateBuildGradle(changeNotesHtml) {
  const p = 'build.gradle.kts';
  const src = fs.readFileSync(p, 'utf8');

  const re = /changeNotes\s*=\s*"""[\s\S]*?"""\.trimIndent\(\)/m;
  const replacement = `changeNotes = """\n${indentForKotlin(changeNotesHtml, 12)}\n""".trimIndent()`;

  if (!re.test(src)) {
    throw new Error('Could not find changeNotes = """...""".trimIndent() in build.gradle.kts');
  }

  // Use a function replacer so `$&`, `$`` and `$'` in the replacement are not treated as special patterns.
  const out = src.replace(re, () => replacement);
  fs.writeFileSync(p, out);
}

function indentForKotlin(text, spaces) {
  const pad = ' '.repeat(spaces);
  return text
    .split('\n')
    .map((l) => (l.length ? pad + l : l))
    .join('\n');
}

function parseArgs(argv) {
  const args = { sinceTag: null, apply: false, maxNew: 3, maxFixes: 4 };

  let i = 2;

  function requireValue(flag) {
    const v = argv[i + 1];
    if (v == null || v.startsWith('--')) {
      throw new Error(`Missing value for ${flag}`);
    }
    i++;
    return v;
  }

  for (; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--sinceTag') args.sinceTag = requireValue('--sinceTag');
    else if (a === '--apply') args.apply = true;
    else if (a === '--maxNew') {
      const v = Number(requireValue('--maxNew'));
      if (!Number.isFinite(v)) throw new Error('Invalid value for --maxNew');
      args.maxNew = v;
    } else if (a === '--maxFixes') {
      const v = Number(requireValue('--maxFixes'));
      if (!Number.isFinite(v)) throw new Error('Invalid value for --maxFixes');
      args.maxFixes = v;
    } else if (a === '--help' || a === '-h') {
      console.log('Usage: node scripts/generate-change-notes.mjs [--sinceTag vX.Y.Z] [--apply] [--maxNew N] [--maxFixes N]');
      process.exit(0);
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv);

  const tag = args.sinceTag ?? latestTag();
  const repo = repoSlug();
  const prs = prNumbersSinceTag(tag);

  const whatsNew = [];
  const fixes = [];

  for (const n of prs) {
    // Stop early once we've filled both sections to avoid excessive API calls.
    if (whatsNew.length >= args.maxNew && fixes.length >= args.maxFixes) break;

    const title = fetchPrTitle(repo, n);
    const c = classify(title);
    if (c.skip) continue;
    const bullet = toBullet(title);
    if (!bullet) continue;

    if (c.section === 'fixes') {
      if (fixes.length < args.maxFixes) fixes.push(bullet);
    } else {
      if (whatsNew.length < args.maxNew) whatsNew.push(bullet);
    }
  }

  const html = renderHtml({ whatsNew, fixes });

  if (args.apply) {
    updateBuildGradle(html);
    console.log('Updated build.gradle.kts changeNotes.');
  } else {
    console.log(html);
  }
}

main();
