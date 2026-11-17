// UI language. Texts live in i18n/<code>.json (English is the fallback for any missing key); place
// type labels in i18n/labels-<code>.json, keyed by the English phrase, taken from the Android app's
// tl_* string resources. Elements in index.html name their text with data-i18n (text content) and
// data-i18n-aria (aria-label).

// [code, English name, native name, BCP 47 tag for speech]. Sorted by English name.
export const LANGS = [
	['ar', 'Arabic', 'العربية', 'ar'],
	['zh', 'Chinese', '中文', 'zh-CN'],
	['cs', 'Czech', 'Čeština', 'cs-CZ'],
	['nl', 'Dutch', 'Nederlands', 'nl-NL'],
	['en', 'English', 'English', 'en-US'],
	['fr', 'French', 'Français', 'fr-FR'],
	['de', 'German', 'Deutsch', 'de-DE'],
	['el', 'Greek', 'Ελληνικά', 'el-GR'],
	['ha', 'Hausa', 'Hausa', 'ha'],
	['it', 'Italian', 'Italiano', 'it-IT'],
	['ja', 'Japanese', '日本語', 'ja-JP'],
	['fa', 'Persian', 'فارسی', 'fa-IR'],
	['pt', 'Portuguese', 'Português', 'pt-BR'],
	['ru', 'Russian', 'Русский', 'ru-RU'],
	['sr', 'Serbian', 'Српски', 'sr-RS'],
	['es', 'Spanish', 'Español', 'es-ES'],
	['sw', 'Swahili', 'Kiswahili', 'sw'],
	['tr', 'Turkish', 'Türkçe', 'tr-TR'],
	['uk', 'Ukrainian', 'Українська', 'uk-UA'],
	['ur', 'Urdu', 'اردو', 'ur-PK'],
	['vi', 'Vietnamese', 'Tiếng Việt', 'vi-VN'],
];
const RTL = new Set(['ar', 'fa', 'ur']);

let en = {}, ui = {}, labels = {};
export let lang = 'en';

async function json(url) {
	try { const r = await fetch(url); return r.ok ? await r.json() : {}; } catch { return {}; }
}

export async function loadLang(code) {
	if (!LANGS.some((l) => l[0] === code)) code = 'en';
	if (!Object.keys(en).length) en = await json('i18n/en.json');
	[ui, labels] = code === 'en' ? [en, {}] : await Promise.all([json(`i18n/${code}.json`), json(`i18n/labels-${code}.json`)]);
	lang = code;
	document.documentElement.lang = code;
	document.documentElement.dir = RTL.has(code) ? 'rtl' : 'ltr';
	applyDom();
}

/** Text for `key` with {placeholders} filled in. */
export function t(key, vars = {}) {
	const s = ui[key] ?? en[key] ?? key;
	return s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));
}

/** A place type label (English phrase) in the current language. */
export function tl(phrase) { return labels[phrase] || phrase; }

export function speechLang() { return LANGS.find((l) => l[0] === lang)[3]; }

export function applyDom(root = document) {
	root.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
	root.querySelectorAll('[data-i18n-aria]').forEach((el) => el.setAttribute('aria-label', t(el.dataset.i18nAria)));
}
