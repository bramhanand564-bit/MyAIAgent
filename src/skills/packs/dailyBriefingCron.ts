import AsyncStorage from '@react-native-async-storage/async-storage';
import { saveMemory } from '../../reader/sqliteFtsDatabase';

const PUBLISH_WEBHOOK_KEY = 'local-agent-publishing-webhook-v1';
const AUTONOMOUS_MODE_KEY = 'local-agent-autonomous-execution-v1';

export interface BriefingResult {
  text: string;
  sources: string[];
  published?: boolean;
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

export async function getPublishingWebhook(): Promise<string> {
  return (await AsyncStorage.getItem(PUBLISH_WEBHOOK_KEY)) || '';
}

export async function setPublishingWebhook(value: string): Promise<void> {
  const cleaned = value.trim();
  if (!cleaned) {
    await AsyncStorage.removeItem(PUBLISH_WEBHOOK_KEY);
    return;
  }
  let parsed: URL;
  try {
    parsed = new URL(cleaned);
  } catch {
    throw new Error('Webhook URL valid nahi hai.');
  }
  if (parsed.protocol !== 'https:') {
    throw new Error('Publishing webhook must use HTTPS.');
  }
  await AsyncStorage.setItem(PUBLISH_WEBHOOK_KEY, parsed.toString());
}

async function publishToConfiguredWebhook(payload: {
  topic: string;
  text: string;
  sources: string[];
}): Promise<void> {
  const endpoint = await getPublishingWebhook();
  if (!endpoint) throw new Error('No publishing webhook is configured in Scheduler settings.');
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({
      event: 'local_ai_agent.daily_briefing',
      publishedAt: new Date().toISOString(),
      ...payload,
    }),
  });
  if (!response.ok) {
    throw new Error('Publishing webhook returned HTTP ' + response.status + '.');
  }
}

export async function isAutonomousExecutionEnabled(): Promise<boolean> {
  return (await AsyncStorage.getItem(AUTONOMOUS_MODE_KEY)) === 'true';
}

export async function runDailyBriefing(
  topic: string,
  publishAutomatically = false,
): Promise<BriefingResult> {
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
    let text = 'Daily briefing — ' + topic + '\n\n' + digest +
      '\n\nHeadlines ko publish karne se pehle source article khol kar verify karein.';
    const sources = headlines.map((item) => item.link).filter(Boolean);
    await saveMemory('daily_briefing', text);

    let published = false;
    if (publishAutomatically) {
      await publishToConfiguredWebhook({ topic, text, sources });
      published = true;
      text += '\n\nConfigured HTTPS webhook ko publish request bhej di gayi.';
    }
    return { text, sources, published };
  } catch (error) {
    return {
      text: 'Daily briefing fetch/publish nahi ho saka: ' + (error instanceof Error ? error.message : 'network error'),
      sources: [url],
      published: false,
    };
  }
}
