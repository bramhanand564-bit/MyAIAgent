import { routeIntent } from './intentRouter';
import { generateGroundedAnswer } from './llamaService';
import { searchDocuments } from '../reader/sqliteFtsDatabase';
import { searchWeb } from '../reader/webSearchEngine';
import {
  parsePhoneIntent,
  type PendingPhoneAction,
} from '../skills/packs/phoneController';
import { researchPublicLeads } from '../skills/packs/leadScraperPipeline';
import { runDailyBriefing } from '../skills/packs/dailyBriefingCron';

export interface AgentResponse {
  text: string;
  pendingAction?: PendingPhoneAction;
  sources?: string[];
}

function formatEvidence(
  items: Array<{ title: string; snippet: string; url: string }>,
): string {
  return items.map((item, index) =>
    '[' + (index + 1) + '] ' + item.title + '\n' + item.snippet + '\nSource: ' + item.url,
  ).join('\n\n');
}

export async function processUserMessage(message: string): Promise<AgentResponse> {
  const cleaned = message.trim();
  if (!cleaned) return { text: 'Apna sawal ya task type kijiye.' };

  const route = routeIntent(cleaned);

  if (route.kind === 'phone') {
    const parsed = parsePhoneIntent(cleaned);
    if (!parsed) {
      return {
        text: 'Phone action samajh nahi aaya. Number ke saath call/WhatsApp request ya exact alarm time likhiye.',
      };
    }
    if ('error' in parsed) return { text: parsed.error };
    return {
      text: 'Confirm karein: ' + parsed.summary + '. Confirm button dabane par Android app/dialer khulega; message khud se send nahi hoga.',
      pendingAction: parsed,
    };
  }

  if (route.kind === 'documents') {
    const hits = await searchDocuments(cleaned, 3);
    if (hits.length === 0) {
      return { text: 'Knowledge Vault mein matching text nahi mila. Pehle Knowledge Vault tab se PDF/TXT/MD/CSV document import kijiye.' };
    }
    const evidence = hits.map((hit) =>
      hit.title + ' (page ' + hit.pageNumber + ')\n' + hit.content,
    ).join('\n\n');
    const answer = await generateGroundedAnswer(cleaned, evidence);
    const citations = hits.map((hit) => hit.title + ', page ' + hit.pageNumber);
    return {
      text: answer || (hits[0]?.content ?? 'Matching text mil gaya, lekin answer generate nahi ho saka.'),
      sources: citations,
    };
  }

  if (route.kind === 'lead_research') {
    const result = await researchPublicLeads(cleaned);
    return { text: result.text, sources: result.sources };
  }

  if (route.kind === 'briefing') {
    const result = await runDailyBriefing('India technology and business');
    return { text: result.text, sources: result.sources };
  }

  if (route.kind === 'web') {
    const results = await searchWeb(cleaned);
    if (results.length === 0) {
      return { text: 'Live web result nahi mila. Internet connection check kijiye ya sawal ko thoda specific banaiye.' };
    }
    const evidence = formatEvidence(results);
    const answer = await generateGroundedAnswer(cleaned, evidence);
    const sourceLabels = results.slice(0, 4).map((item) => item.title + ' — ' + item.url);
    return {
      text: answer || results.slice(0, 3).map((item, index) =>
        (index + 1) + '. ' + item.title + ': ' + item.snippet,
      ).join('\n\n'),
      sources: sourceLabels,
    };
  }

  const localAnswer = await generateGroundedAnswer(
    cleaned,
    'No verified external evidence was supplied. Do not present uncertain facts as verified.',
  );
  return {
    text: localAnswer || 'Main ready hoon. Local model download karne ke liye Chat screen par “Download local brain” dabaiye; ya web/document search ke liye apna sawal likhiye.',
  };
}
