import { AppState, type AppStateStatus } from 'react-native';
import { initLlama } from 'llama.rn';
import { getHardwareProfile } from '../config/hardwareProfile';
import { downloadModel, getModelPath } from '../config/modelRegistry';

interface LlamaRuntime {
  completion(options: Record<string, unknown>): Promise<{ text: string }>;
  release(): Promise<void>;
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

export async function generateGroundedAnswer(
  question: string,
  evidence: string,
): Promise<string | null> {
  if (!context) return null;
  const prompt =
    'You are a compact bilingual Hindi-English assistant. Answer the user in the language they used. ' +
    'Use only the EVIDENCE below for factual claims. If it does not answer the question, say that the evidence is insufficient. ' +
    'Do not invent dates, names, citations or actions. Keep the answer concise.\n\n' +
    'EVIDENCE:\n' + evidence.slice(0, 5000) + '\n\n' +
    'USER QUESTION:\n' + question.slice(0, 1000) + '\n\n' +
    'ASSISTANT:';

  try {
    const result = await context.completion({
      prompt,
      n_predict: 160,
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
