import { searchWeb, type WebSearchResult } from '../../reader/webSearchEngine';

export interface LeadResearchResult {
  text: string;
  sources: string[];
  results: WebSearchResult[];
}

export async function researchPublicLeads(request: string): Promise<LeadResearchResult> {
  const results = await searchWeb(request + ' public business directory official website contact');
  if (results.length === 0) {
    return {
      text: 'Public lead research ke liye web snippets nahi mile. Area aur business category ko specific karke retry kijiye.',
      sources: [],
      results: [],
    };
  }

  const summaries = results.slice(0, 5).map((item, index) =>
    (index + 1) + '. ' + item.title + '\n' + item.snippet,
  );
  return {
    text: 'Public web snippets se yeh preliminary leads mili hain. Inhe verified contact list na samjhein; outreach se pehle business details khud verify karein.\n\n' +
      summaries.join('\n\n') +
      '\n\nDraft outreach: Namaste, hum aapke business ki online presence improve karne mein madad kar sakte hain. Kya aap short details dekhna chahenge?',
    sources: results.slice(0, 5).map((item) => item.url),
    results,
  };
}
