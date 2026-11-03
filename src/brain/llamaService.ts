import { AppState, type AppStateStatus } from 'react-native';
import { initLlama } from 'llama.rn';
import { getHardwareProfile } from '../config/hardwareProfile';
import { downloadModel, getModelPath } from '../config/modelRegistry';
import { ROUTE_GBNF, safeParseAgentJson } from './grammarRules';

interface LlamaRuntime {
  completion(options: Record<string, unknown>): Promise<{ text: string }>;
  release(): Promise<void>;
}

export interface StructuredAgentRoute {
  thought: string;
  action: 'call_skill' | 'web_search' | 'read_document' | 'direct_reply';
  skill_id: string | null;
  parameters: Record<string, unknown>;
  reply_text: string;
}

let context: LlamaRuntime | null = null;
let loadingPromise: Promise<void> | null = null;
let appStateSubscription: ReturnType<typeof AppState.addEventListener> | null = null;

export function isLlamaLoaded(): boolean {
  return context !== null;
}

export async function loadLocalModel(
  onProgress?: (percent: number) => void,
): Promise<void> {
  if (context) return;
  if (loadingPromise) return loadingPromise;

  loadingPromise = (async () => {
    const profile = await getHardwareProfile();
    const modelPath = await downloadModel(profile.modelId, onProgress);
    const runtime = await initLlama({
      model: 'file://' + modelPath,
      use_mlock: false,
      n_ctx: profile.contextSize,
      n_gpu_layers: profile.gpuLayers,
      n_threads: 4,
    } as never);
    context = runtime as unknown as LlamaRuntime;
  })();

  try {
    await loadingPromise;
  } finally {
    loadingPromise = null;
  }
}

export async function unloadLocalModel(): Promise<void> {
  const active = context;
  context = null;
  if (active) {
    try {
      await active.release();
    } catch {
      // Native context may already have been released by the platform.
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export async function generateStructuredRoute(
  userMessage: string,
  relevantSkillSchemas: string,
): Promise<StructuredAgentRoute | null> {
  if (!context) return null;
  const system =
    'You route requests for a mobile assistant. Do not execute actions. ' +
    'Choose only from call_skill, web_search, read_document, direct_reply. ' +
    'Use a listed skill id only. If unsure, choose direct_reply and ask a clarifying question. ' +
    'Return only the required JSON object.';
  const prompt =
    '<|im_start|>system\n' + system +
    '\nAVAILABLE SKILLS:\n' + (relevantSkillSchemas || 'No matching skills') +
    '\nJSON fields: thought, action, skill_id, parameters, reply_text.' +
    '<|im_end|>\n<|im_start|>user\n' + userMessage.slice(0, 700) +
    '<|im_end|>\n<|im_start|>assistant\n';

  try {
    const result = await context.completion({
      prompt,
      grammar: ROUTE_GBNF,
      n_predict: 180,
      temperature: 0.1,
      stop: ['<|im_end|>', '</s>', '<|endoftext|>'],
    });
    const parsed = safeParseAgentJson(result.text);
    if (!isRecord(parsed)) return null;
    const action = parsed.action;
    if (
      action !== 'call_skill' &&
      action !== 'web_search' &&
      action !== 'read_document' &&
      action !== 'direct_reply'
    ) return null;
    if (!isRecord(parsed.parameters)) return null;
    return {
      thought: typeof parsed.thought === 'string' ? parsed.thought.slice(0, 180) : '',
      action,
      skill_id: typeof parsed.skill_id === 'string' ? parsed.skill_id.slice(0, 80) : null,
      parameters: parsed.parameters,
      reply_text: typeof parsed.reply_text === 'string' ? parsed.reply_text.slice(0, 500) : '',
    };
  } catch {
    return null;
  }
}

export async function generateGroundedAnswer(
  question: string,
  evidence: string,
): Promise<string | null> {
  if (!context) return null;
  const system =
    'You are a compact bilingual Hindi-English assistant. Answer in the language the user used. ' +
    'Use only the EVIDENCE below for factual claims. If it does not answer the question, say the evidence is insufficient. ' +
    'Do not invent dates, names, citations or actions. Keep the answer concise.';
  const prompt =
    '<|im_start|>system\n' + system + '<|im_end|>\n' +
    '<|im_start|>user\n' +
    'EVIDENCE:\n' + evidence.slice(0, 1800) + '\n\n' +
    'QUESTION:\n' + question.slice(0, 500) +
    '<|im_end|>\n<|im_start|>assistant\n';

  try {
    const result = await context.completion({
      prompt,
      n_predict: 128,
      temperature: 0.1,
      top_p: 0.85,
      stop: ['<|im_end|>', '</s>', '<|endoftext|>'],
    });
    const answer = result.text.trim();
    return answer.length > 0 ? answer : null;
  } catch {
    return null;
  }
}

export function attachModelLifecycleCleanup(): () => void {
  if (appStateSubscription) appStateSubscription.remove();
  appStateSubscription = AppState.addEventListener('change', (state: AppStateStatus) => {
    if (state === 'background' || state === 'inactive') {
      void unloadLocalModel();
    }
  });
  return () => {
    appStateSubscription?.remove();
    appStateSubscription = null;
    void unloadLocalModel();
  };
}

export async function getSelectedModelPath(): Promise<string> {
  const profile = await getHardwareProfile();
  return getModelPath(profile.modelId);
}
