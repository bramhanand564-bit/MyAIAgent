import AsyncStorage from '@react-native-async-storage/async-storage';

export interface SkillDefinition {
  id: string;
  name: string;
  description: string;
  keywords: string[];
  enabled: boolean;
  handler: 'phone' | 'documents' | 'lead_research' | 'daily_briefing';
  parametersSchema: Record<string, unknown>;
}

const BUILT_IN_SKILLS: SkillDefinition[] = [
  {
    id: 'phone_controller',
    name: 'Phone Controller',
    description: 'Open Android dialer, WhatsApp composer and supported alarm intents.',
    keywords: ['call', 'phone', 'dial', 'whatsapp', 'alarm', 'कॉल', 'फोन', 'अलार्म'],
    enabled: true,
    handler: 'phone',
    parametersSchema: { action: 'call | whatsapp | set_alarm', phone: 'string', message: 'string', time: 'HH:MM' },
  },
  {
    id: 'pdf_book_reader',
    name: 'PDF & Book Reader',
    description: 'Index digital PDFs and text files, then search relevant passages.',
    keywords: ['pdf', 'book', 'document', 'notes', 'chapter', 'पीडीएफ', 'किताब'],
    enabled: true,
    handler: 'documents',
    parametersSchema: { query: 'string', top_k: 'number (max 3)' },
  },
  {
    id: 'public_lead_research',
    name: 'Public Lead Research',
    description: 'Research public web snippets and draft outreach; no automatic scraping or sending.',
    keywords: ['lead', 'business', 'prospect', 'outreach', 'बिजनेस', 'लीड'],
    enabled: true,
    handler: 'lead_research',
    parametersSchema: { niche: 'string', area: 'string' },
  },
  {
    id: 'daily_briefing',
    name: 'Daily Briefing',
    description: 'Fetch public RSS headlines and prepare a concise news digest.',
    keywords: ['daily briefing', 'news', 'today', 'morning', 'खबर', 'समाचार'],
    enabled: true,
    handler: 'daily_briefing',
    parametersSchema: { topic: 'string' },
  },
];

const STORAGE_KEY = 'local-agent-enabled-skills-v1';
let enabledIds: Set<string> | null = null;

export async function loadSkillPreferences(): Promise<void> {
  try {
    const stored = await AsyncStorage.getItem(STORAGE_KEY);
    const values = stored ? JSON.parse(stored) as string[] : BUILT_IN_SKILLS.map((skill) => skill.id);
    enabledIds = new Set(values.filter((id) => BUILT_IN_SKILLS.some((skill) => skill.id === id)));
  } catch {
    enabledIds = new Set(BUILT_IN_SKILLS.map((skill) => skill.id));
  }
}

export function getBuiltinSkills(): SkillDefinition[] {
  const enabled = enabledIds ?? new Set(BUILT_IN_SKILLS.map((skill) => skill.id));
  return BUILT_IN_SKILLS.map((skill) => ({ ...skill, enabled: enabled.has(skill.id) }));
}

export async function setSkillEnabled(skillId: string, value: boolean): Promise<void> {
  if (!BUILT_IN_SKILLS.some((skill) => skill.id === skillId)) {
    throw new Error('Unknown built-in skill: ' + skillId);
  }
  if (!enabledIds) await loadSkillPreferences();
  if (value) enabledIds?.add(skillId);
  else enabledIds?.delete(skillId);
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(enabledIds ?? [])));
}

export function getSkillSchemasForPrompt(query: string): string {
  const tokens = query.toLowerCase().split(/[^a-z0-9\u0900-\u097f]+/).filter(Boolean);
  return getBuiltinSkills()
    .filter((skill) => skill.enabled)
    .map((skill) => ({
      skill,
      score: skill.keywords.filter((keyword) =>
        tokens.some((token) => token.includes(keyword.toLowerCase()) || keyword.toLowerCase().includes(token)),
      ).length,
    }))
    .filter((item) => item.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 2)
    .map((item) => JSON.stringify({
      id: item.skill.id,
      description: item.skill.description,
      schema: item.skill.parametersSchema,
    }))
    .join('\n');
}
