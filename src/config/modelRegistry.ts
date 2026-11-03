import RNFS from 'react-native-fs';
import type { ModelId } from './hardwareProfile';

export interface ModelDefinition {
  id: ModelId;
  displayName: string;
  parameterCount: string;
  fileName: string;
  url: string;
  expectedBytes: number;
  minFreeBytes: number;
  license: string;
}

export const MODEL_REGISTRY: Record<ModelId, ModelDefinition> = {
  'qwen2.5-0.5b-q4km': {
    id: 'qwen2.5-0.5b-q4km',
    displayName: 'Qwen2.5 0.5B Instruct Q4_K_M',
    parameterCount: '0.5B',
    fileName: 'qwen2.5-0.5b-instruct-q4_k_m.gguf',
    url: 'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf',
    expectedBytes: 500_000_000,
    minFreeBytes: 650_000_000,
    license: 'Apache-2.0 (verify the model card before redistribution)',
  },
  'qwen2.5-1.5b-q4km': {
    id: 'qwen2.5-1.5b-q4km',
    displayName: 'Qwen2.5 1.5B Instruct Q4_K_M',
    parameterCount: '1.5B',
    fileName: 'qwen2.5-1.5b-instruct-q4_k_m.gguf',
    url: 'https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf',
    expectedBytes: 1_150_000_000,
    minFreeBytes: 1_400_000_000,
    license: 'Apache-2.0 (verify the model card before redistribution)',
  },
};

export function getModelDirectory(): string {
  return RNFS.DocumentDirectoryPath + '/models';
}

export function getModelPath(modelId: ModelId): string {
  return getModelDirectory() + '/' + MODEL_REGISTRY[modelId].fileName;
}

export async function downloadModel(
  modelId: ModelId,
  onProgress?: (percent: number) => void,
): Promise<string> {
  const definition = MODEL_REGISTRY[modelId];
  const directory = getModelDirectory();
  const destination = getModelPath(modelId);
  await RNFS.mkdir(directory);

  const fsInfo = await RNFS.getFSInfo();
  if (fsInfo.freeSpace < definition.minFreeBytes) {
    throw new Error(
      'Not enough free storage. Please free at least ' +
      Math.ceil(definition.minFreeBytes / (1024 ** 2)) + ' MB and retry.',
    );
  }

  if (await RNFS.exists(destination)) {
    const current = await RNFS.stat(destination);
    if (Number(current.size) >= definition.expectedBytes * 0.9) {
      onProgress?.(100);
      return destination;
    }
    await RNFS.unlink(destination);
  }

  const task = RNFS.downloadFile({
    fromUrl: definition.url,
    toFile: destination,
    begin: (event) => {
      if (event.contentLength > 0) onProgress?.(0);
    },
    progress: (event) => {
      if (event.contentLength > 0) {
        onProgress?.(Math.min(99, Math.floor((event.bytesWritten / event.contentLength) * 100)));
      }
    },
    progressDivider: 2,
    connectionTimeout: 30_000,
    readTimeout: 120_000,
    background: true,
  });

  try {
    const result = await task.promise;
    if (result.statusCode < 200 || result.statusCode >= 300) {
      throw new Error('Model download failed with HTTP ' + result.statusCode);
    }
    const saved = await RNFS.stat(destination);
    if (Number(saved.size) < definition.expectedBytes * 0.9) {
      throw new Error('Downloaded model file is incomplete. Please retry the download.');
    }
    onProgress?.(100);
    return destination;
  } catch (error) {
    try {
      if (await RNFS.exists(destination)) await RNFS.unlink(destination);
    } catch {
      // Preserve the original download error.
    }
    throw error;
  }
}
