import {
  getCachedSearch,
  saveCachedSearch,
  type CachedSearch,
} from './sqliteFtsDatabase';

export interface WebSearchResult {
  title: string;
  snippet: string;
  url: string;
}

function hashQuery(value: string): string {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16);
}

function decodeHtml(value: string): string {
  return value
    .replace(/<[^>]*>/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#(\d+);/g, (_match, code: string) => String.fromCharCode(Number(code)))
    .replace(/\s+/g, ' ')
    .trim();
}

function collectRelatedTopics(items: unknown[], output: WebSearchResult[]): void {
  for (const item of items) {
    if (!item || typeof item !== 'object') continue;
    const value = item as Record<string, unknown>;
    if (Array.isArray(value.Topics)) {
      collectRelatedTopics(value.Topics, output);
      continue;
    }
    const text = typeof value.Text === 'string' ? value.Text : '';
    const url = typeof value.FirstURL === 'string' ? value.FirstURL : '';
    if (text && url) output.push({ title: text.slice(0, 100), snippet: text, url });
  }
}

async function fetchDuckDuckGoInstant(query: string): Promise<WebSearchResult[]> {
  const url = 'https://api.duckduckgo.com/?q=' + encodeURIComponent(query) +
    '&format=json&no_html=1&skip_disambig=1';
  const response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error('DuckDuckGo request failed: ' + response.status);
  const data = await response.json() as Record<string, unknown>;
  const results: WebSearchResult[] = [];
  const abstract = typeof data.AbstractText === 'string' ? data.AbstractText : '';
  const abstractUrl = typeof data.AbstractURL === 'string' ? data.AbstractURL : '';
  const heading = typeof data.Heading === 'string' ? data.Heading : query;
  if (abstract) results.push({ title: heading, snippet: abstract, url: abstractUrl || url });
  if (Array.isArray(data.RelatedTopics)) collectRelatedTopics(data.RelatedTopics, results);
  return results.slice(0, 6);
}

async function fetchDuckDuckGoHtml(query: string): Promise<WebSearchResult[]> {
  const url = 'https://html.duckduckgo.com/html/?q=' + encodeURIComponent(query);
  const response = await fetch(url, { headers: { Accept: 'text/html' } });
  if (!response.ok) throw new Error('DuckDuckGo HTML search failed: ' + response.status);
  const html = await response.text();
  const results: WebSearchResult[] = [];
  const anchors = Array.from(html.matchAll(/<a\b([^>]*\bclass=["'][^"']*result__a[^"']*["'][^>]*)>([\s\S]*?)<\/a>/gi));

  for (const match of anchors) {
    const attributes = match[1] || '';
    const href = attributes.match(/\bhref=["']([^"']+)["']/i)?.[1] || '';
    const title = decodeHtml(match[2] || '');
    if (!href || !title) continue;

    let target = href.replace(/&amp;/g, '&');
    const wrapped = target.match(/[?&]uddg=([^&]+)/);
    if (wrapped?.[1]) {
      try { target = decodeURIComponent(wrapped[1]); } catch { /* keep the original href */ }
    } else if (target.startsWith('//')) {
      target = 'https:' + target;
    } else if (target.startsWith('/')) {
      target = 'https://duckduckgo.com' + target;
    }
    if (!/^https?:\/\//i.test(target)) continue;

    const index = match.index ?? 0;
    const block = html.slice(index, index + 3000);
    const snippetMatch = block.match(/class=["'][^"']*result__snippet[^"']*["'][^>]*>([\s\S]*?)<\/(?:a|div|span)>/i);
    const snippet = decodeHtml(snippetMatch?.[1] || title);
    if (!results.some((result) => result.url === target)) {
      results.push({ title: title.slice(0, 180), snippet: snippet.slice(0, 700), url: target });
    }
    if (results.length >= 6) break;
  }
  return results;
}

async function fetchWikipedia(query: string): Promise<WebSearchResult[]> {
  const url = 'https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=' +
    encodeURIComponent(query) + '&gsrlimit=5&prop=extracts&exintro=1&explaintext=1&format=json&origin=*';
  const response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error('Wikipedia request failed: ' + response.status);
  const data = await response.json() as {
    query?: { pages?: Record<string, { title?: string; extract?: string; pageid?: number }> };
  };
  return Object.values(data.query?.pages ?? {}).map((page) => ({
    title: page.title || 'Wikipedia result',
    snippet: (page.extract || '').slice(0, 1200),
    url: page.pageid ? 'https://en.wikipedia.org/?curid=' + page.pageid : 'https://www.wikipedia.org/',
  })).filter((item) => item.snippet.length > 0);
}

export async function searchWeb(query: string): Promise<WebSearchResult[]> {
  const cleaned = query.trim().slice(0, 180);
  if (!cleaned) return [];
  const hash = hashQuery(cleaned.toLowerCase());
  const cached = await getCachedSearch(hash);
  if (cached) return cached.results;

  let results: WebSearchResult[] = [];
  try { results = await fetchDuckDuckGoInstant(cleaned); } catch { results = []; }
  if (results.length === 0) {
    try { results = await fetchDuckDuckGoHtml(cleaned); } catch { results = []; }
  }
  if (results.length === 0) {
    try { results = await fetchWikipedia(cleaned); } catch { results = []; }
  }

  if (results.length > 0) {
    const value: CachedSearch = {
      results,
      sourceUrl: results[0]?.url || 'https://duckduckgo.com/',
    };
    await saveCachedSearch(hash, cleaned, value);
  }
  return results;
}
