import AsyncStorage from '@react-native-async-storage/async-storage';
import DocumentPicker from 'react-native-document-picker';
import RNFS from 'react-native-fs';

export interface SkillDefinition {
  id: string;
  name: string;
  description: string;
  keywords: string[];
  enabled: boolean;
  handler: 'phone' | 'documents' | 'lead_research' | 'daily_briefing';
  parametersSchema: Record<string, unknown>;
}

export interface ImportedSkillPackMetadata {
  id: string;
  name: string;
  description: string;
  keywords: string[];
  parametersSchema: Record<string, unknown>;
  importedAt: number;
  executable: false;
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
const IMPORTED_STORAGE_KEY = 'local-agent-imported-skill-metadata-v1';
let enabledIds: Set<string> | null = null;
let importedSkills: ImportedSkillPackMetadata[] = [];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function validateImportedSkill(input: unknown): ImportedSkillPackMetadata {
  if (!isRecord(input)) throw new Error('Skill JSON must contain one object.');
  const rawId = typeof input.id === 'string' ? input.id.toLowerCase() : '';
  const id = rawId.replace(/[^a-z0-9_-]/g, '').slice(0, 64);
  if (!/^[a-z0-9][a-z0-9_-]{1,63}$/.test(id)) {
    throw new Error('Skill id must be 2–64 lowercase letters, digits, underscores or hyphens.');
  }
  const name = typeof input.name === 'string' ? input.name.trim().slice(0, 80) : '';
  const descriptionValue = input.short_description ?? input.description;
  const description = typeof descriptionValue === 'string' ? descriptionValue.trim().slice(0, 240) : '';
  const keywordValue = input.trigger_keywords ?? input.keywords;
  if (!name || !description || !Array.isArray(keywordValue)) {
    throw new Error('Skill JSON needs name, short_description/description and trigger_keywords/keywords.');
  }
  const keywords = keywordValue
    .filter((item): item is string => typeof item === 'string')
    .map((item) => item.trim().slice(0, 40))
    .filter(Boolean)
    .slice(0, 40);
  if (keywords.length === 0) throw new Error('Add at least one keyword to the skill JSON.');
  const schemaValue = input.parameters_schema ?? input.parametersSchema ?? {};
  if (!isRecord(schemaValue)) throw new Error('parameters_schema must be a JSON object.');

  return {
    id,
    name,
    description,
    keywords,
    parametersSchema: schemaValue,
    importedAt: Date.now(),
    executable: false,
  };
}

export async function loadSkillPreferences(): Promise<void> {
  try {
    const stored = await AsyncStorage.getItem(STORAGE_KEY);
    const values = stored ? JSON.parse(stored) as string[] : BUILT_IN_SKILLS.map((skill) => skill.id);
    enabledIds = new Set(values.filter((id) => BUILT_IN_SKILLS.some((skill) => skill.id === id)));
  } catch {
    enabledIds = new Set(BUILT_IN_SKILLS.map((skill) => skill.id));
  }
  try {
    const stored = await AsyncStorage.getItem(IMPORTED_STORAGE_KEY);
    const parsed = stored ? JSON.parse(stored) as unknown : [];
    importedSkills = Array.isArray(parsed)
      ? parsed.filter((item): item is ImportedSkillPackMetadata =>
          isRecord(item) &&
          typeof item.id === 'string' &&
          typeof item.name === 'string' &&
          Array.isArray(item.keywords) &&
          item.executable === false,
        ).slice(0, 30)
      : [];
  } catch {
    importedSkills = [];
  }
}

export function getBuiltinSkills(): SkillDefinition[] {
  const enabled = enabledIds ?? new Set(BUILT_IN_SKILLS.map((skill) => skill.id));
  return BUILT_IN_SKILLS.map((skill) => ({ ...skill, enabled: enabled.has(skill.id) }));
}

export function getImportedSkillMetadata(): ImportedSkillPackMetadata[] {
  return importedSkills.map((item) => ({ ...item, keywords: [...item.keywords], parametersSchema: { ...item.parametersSchema } }));
}

export async function importSkillPackMetadata(): Promise<ImportedSkillPackMetadata> {
  const picked = await DocumentPicker.pickSingle({
    type: ['application/json', 'text/json'],
    copyTo: 'documentDirectory',
  });
  const uri = picked.fileCopyUri || picked.uri;
  if (!uri || !uri.startsWith('file://')) {
    throw new Error('Could not create a private local copy of the skill JSON file.');
  }
  const contents = await RNFS.readFile(uri.slice('file://'.length), 'utf8');
  if (contents.length > 256 * 1024) throw new Error('Skill JSON is limited to 256 KB.');
  const skill = validateImportedSkill(JSON.parse(contents) as unknown);
  const next = importedSkills.filter((item) => item.id !== skill.id);
  next.push(skill);
  if (next.length > 30) throw new Error('The Skill Store supports up to 30 imported metadata packs.');
  importedSkills = next;
  await AsyncStorage.setItem(IMPORTED_STORAGE_KEY, JSON.stringify(importedSkills));
  return skill;
}

export async function setSkillEnabled(skillId: string, value: boolean): Promise<void> {
  if (!BUILT_IN_SKILLS.some((skill) => skill.id === skillId)) {
    throw new Error('Only built-in allow-listed skills can be enabled for execution.');
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
