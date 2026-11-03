import { getBuiltinSkills, type SkillDefinition } from '../skills/skillRegistry';
import { parsePhoneIntent } from '../skills/packs/phoneController';

export type IntentRoute =
  | { kind: 'phone'; skills: SkillDefinition[] }
  | { kind: 'documents'; skills: SkillDefinition[] }
  | { kind: 'lead_research'; skills: SkillDefinition[] }
  | { kind: 'briefing'; skills: SkillDefinition[] }
  | { kind: 'web'; skills: SkillDefinition[] }
  | { kind: 'direct'; skills: SkillDefinition[] };

function topSkills(query: string): SkillDefinition[] {
  const words = query.toLowerCase().split(/[^a-z0-9\u0900-\u097f]+/).filter(Boolean);
  const scored = getBuiltinSkills().filter((skill) => skill.enabled).map((skill) => {
    const score = skill.keywords.reduce(
      (total, keyword) => total + (words.some((word) => keyword.toLowerCase().includes(word) || word.includes(keyword.toLowerCase())) ? 1 : 0),
      0,
    );
    return { skill, score };
  });
  return scored
    .filter((item) => item.score > 0)
    .sort((left, right) => right.score - left.score)
    .slice(0, 2)
    .map((item) => item.skill);
}

export function routeIntent(query: string): IntentRoute {
  const text = query.toLowerCase();
  const phoneIntent = parsePhoneIntent(query);
  if (phoneIntent || /\b(call|dial|phone|whatsapp|alarm|reminder)\b|कॉल|फोन|व्हाट्सऐप|अलार्म|रिमाइंडर/.test(text)) {
    return { kind: 'phone', skills: topSkills(query) };
  }
  if (/\b(lead|prospect|business list|outreach|website nahi|without website)\b|लीड|बिजनेस लिस्ट|व्यापार/.test(text)) {
    return { kind: 'lead_research', skills: topSkills(query) };
  }
  if (/\b(daily briefing|morning briefing|news summary|आज की खबर|briefing)\b/.test(text)) {
    return { kind: 'briefing', skills: topSkills(query) };
  }
  if (/\b(pdf|document|book|file|notes|chapter)\b|पीडीएफ|किताब|दस्तावेज|नोट्स/.test(text)) {
    return { kind: 'documents', skills: topSkills(query) };
  }
  if (/\b(who|what|when|where|why|how|latest|today|current|news|search|find)\b|कौन|क्या|कब|कहाँ|क्यों|कैसे|आज|ताज़ा|खोज/.test(text)) {
    return { kind: 'web', skills: topSkills(query) };
  }
  return { kind: 'direct', skills: topSkills(query) };
}
