import { saveMemory } from '../../reader/sqliteFtsDatabase';

export interface BriefingResult {
  text: string;
  sources: string[];
}

function decodeXml(value: string): string {
  return value
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export async function runDailyBriefing(topic: string): Promise<BriefingResult> {
  const url = 'https://news.google.com/rss/search?q=' + encodeURIComponent(topic) +
    '&hl=en-IN&gl=IN&ceid=IN:en';
  try {
    const response = await fetch(url, { headers: { Accept: 'application/rss+xml, application/xml, text/xml' } });
    if (!response.ok) throw new Error('RSS request failed: ' + response.status);
    const xml = await response.text();
    const items = Array.from(xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)).slice(0, 8);
    const headlines = items.map((match) => {
      const block = match[1] || '';
      const title = decodeXml(block.match(/<title>([\s\S]*?)<\/title>/i)?.[1] || '');
      const link = decodeXml(block.match(/<link>([\s\S]*?)<\/link>/i)?.[1] || '');
      const description = decodeXml(block.match(/<description>([\s\S]*?)<\/description>/i)?.[1] || '');
      return { title, link, description };
    }).filter((item) => item.title.length > 0);

    if (headlines.length === 0) {
      return { text: 'RSS feed se headlines nahi mile.', sources: [url] };
    }
    const digest = headlines.map((item, index) =>
      (index + 1) + '. ' + item.title +
      (item.description ? '\n' + item.description.slice(0, 220) : ''),
    ).join('\n\n');
    const text = 'Daily briefing — ' + topic + '\n\n' + digest +
      '\n\nHeadlines ko publish karne se pehle source article khol kar verify karein.';
    await saveMemory('daily_briefing', text);
    return { text, sources: headlines.map((item) => item.link).filter(Boolean) };
  } catch (error) {
    return {
      text: 'Daily briefing fetch nahi ho saka: ' + (error instanceof Error ? error.message : 'network error'),
      sources: [url],
    };
  }
}
