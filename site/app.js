import './scene.js';

/* Latest release metadata lives in site/version.json, written by the release
   workflow. The raw copy on main is the fallback when the local file is stale. */
const MANIFEST_SOURCES = [
  './version.json',
  'https://raw.githubusercontent.com/sahilsahilimtiyaz-code/J2/main/site/version.json'
];
const RELEASES_URL = 'https://github.com/sahilsahilimtiyaz-code/J2/releases';
const LATEST_PAGE = RELEASES_URL + '/latest';

const $ = (id) => document.getElementById(id);

const androidNode = document.querySelector('[data-android]');
const android = (androidNode && androidNode.textContent.trim()) || 'Android 12+';

function formatDate(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function notesToItems(text) {
  return String(text || '')
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*[-*\u2022]\s*/, '').trim())
    .filter((line) => line.length > 0);
}

function applyManifest(manifest) {
  const name = String(manifest.versionName || '').trim();
  const code = Number(manifest.versionCode) || 0;
  const label = name ? 'v' + name : (code ? 'build ' + code : 'latest build');
  const href = String(manifest.apkUrl || '').trim() || LATEST_PAGE;
  const published = formatDate(manifest.releasedAt);

  for (const id of ['download-btn', 'head-download', 'card-download']) {
    const el = $(id);
    if (el) el.href = href;
  }

  const badge = $('version-badge');
  if (badge) badge.textContent = 'Latest \u00b7 ' + label;

  const stat = $('stat-version');
  if (stat) stat.textContent = label;

  const sub = $('download-sub');
  if (sub) sub.textContent = 'Signed APK \u00b7 ' + android;

  const meta = $('apk-meta');
  if (meta) {
    meta.textContent = label + ' \u00b7 ' + android + ' \u00b7 installs over the previous version';
  }

  const releaseVersion = $('release-version');
  if (releaseVersion) releaseVersion.textContent = label;

  const releaseDate = $('release-date');
  if (releaseDate) {
    releaseDate.textContent = published
      ? 'Published ' + published + ' by the release workflow.'
      : 'Published by the release workflow.';
  }

  const cardVersion = $('card-version');
  if (cardVersion) cardVersion.textContent = label;

  const cardDate = $('card-date');
  if (cardDate) cardDate.textContent = published || 'Latest';

  const notes = notesToItems(manifest.releaseNotes);
  const list = $('release-notes');
  if (list) {
    list.textContent = '';
    const items = notes.length
      ? notes
      : ['This build ships with the usual refinements. See the GitHub release for details.'];
    for (const note of items) {
      const item = document.createElement('li');
      item.textContent = note;
      list.appendChild(item);
    }
  }

  const checksum = $('checksum');
  const copy = $('copy-checksum');
  const sha = String(manifest.sha256 || '').trim();
  if (checksum) checksum.textContent = sha || 'published with each build';
  if (copy) {
    copy.disabled = !sha;
    copy.style.opacity = sha ? '1' : '0.45';
  }
}

/* Reads every reachable manifest and keeps the newest build, so a stale bundled
   file can never advertise an older version than the published one. */
async function loadManifest() {
  const found = [];
  for (const source of MANIFEST_SOURCES) {
    try {
      const response = await fetch(source, { cache: 'no-store' });
      if (!response.ok) continue;
      const manifest = await response.json();
      if (manifest && Number(manifest.versionCode) > 0) found.push(manifest);
    } catch (error) {
      /* try the next source */
    }
  }
  if (!found.length) return null;
  return found.reduce((newest, current) =>
    (Number(current.versionCode) || 0) > (Number(newest.versionCode) || 0) ? current : newest
  );
}

function wireCopy() {
  const button = $('copy-checksum');
  if (!button) return;
  button.addEventListener('click', async () => {
    const text = ($('checksum') || {}).textContent || '';
    if (!text || text === 'published with each build') return;
    try {
      await navigator.clipboard.writeText(text);
      button.textContent = 'Copied';
    } catch (error) {
      button.textContent = 'Copy failed';
    }
    setTimeout(() => {
      button.textContent = 'Copy';
    }, 1600);
  });
}

function wireHeader() {
  const head = document.querySelector('.site-head');
  if (!head) return;
  const update = () => head.classList.toggle('is-stuck', window.scrollY > 8);
  update();
  window.addEventListener('scroll', update, { passive: true });
}

/* Kept at module scope: an observer referenced only by its own callback can be
   garbage collected, which silently stops every reveal animation. */
let revealObserver = null;
let revealObserverReported = false;

function wireReveal() {
  const targets = Array.from(document.querySelectorAll('.reveal'));
  if (!targets.length) return;
  const revealAll = () => targets.forEach((el) => el.classList.add('in'));

  if (!('IntersectionObserver' in window)) {
    revealAll();
    return;
  }

  try {
    document.documentElement.classList.add('js');
    revealObserver = new IntersectionObserver((entries) => {
      revealObserverReported = true;
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('in');
          revealObserver.unobserve(entry.target);
        }
      }
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    targets.forEach((el) => revealObserver.observe(el));

    /* Safety net: only if the observer never reported at all (prerenderer or a
       broken implementation) do we show everything, so the effect survives. */
    setTimeout(() => {
      if (!revealObserverReported) revealAll();
    }, 1500);
  } catch (error) {
    revealAll();
    document.documentElement.classList.remove('js');
  }
}

const year = $('year');
if (year) year.textContent = String(new Date().getFullYear());

wireCopy();
wireHeader();
wireReveal();

loadManifest().then((manifest) => {
  if (manifest) applyManifest(manifest);
});
