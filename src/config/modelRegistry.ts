import { NativeModules } from 'react-native';
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

interface NativeResumableDownloader {
  download(
    url: string,
    destinationPath: string,
    expectedBytes: number,
    onProgress: (percent: number) => void,
  ): Promise<string>;
}

const nativeDownloader = NativeModules.ResumableDownload as NativeResumableDownloader | undefined;

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
  const partialPath = destination + '.part';
  await RNFS.mkdir(directory);

  const fsInfo = await RNFS.getFSInfo();
  if (fsInfo.freeSpace < definition.minFreeBytes) {
    throw new Error(
      'Not enough free storage. Please free at least ' +
      Math.ceil(definition.minFreeBytes / (1024 ** 2)) + ' MB and retry.',
    );
  }

  if (await RNFS.exists(destination)) {
    const currentFile = await RNFS.stat(destination);
    if (Number(currentFile.size) >= definition.expectedBytes * 0.9) {
      onProgress?.(100);
      return destination;
    }
    await RNFS.unlink(destination);
  }

  let existingBytes = 0;
  if (await RNFS.exists(partialPath)) {
    const partial = await RNFS.stat(partialPath);
    existingBytes = Number(partial.size);
  }
  if (existingBytes > 0) {
    onProgress?.(
      Math.min(99, Math.floor((existingBytes / definition.expectedBytes) * 100)),
    );
  } else {
    onProgress?.(0);
  }

  if (!nativeDownloader) {
    throw new Error(
      'Resumable Android downloader is missing from this APK. Install the latest GitHub Actions build.',
    );
  }

  // The native module independently stats the same .part file and sends
  // Range: bytes=<existingBytes>-; it preserves the partial file on network errors.
  try {
    const completedPath = await nativeDownloader.download(
      definition.url,
      destination,
      definition.expectedBytes,
      (percent) => onProgress?.(Math.max(0, Math.min(100, Math.floor(percent)))),
    );
    const saved = await RNFS.stat(completedPath);
    if (Number(saved.size) < definition.expectedBytes * 0.9) {
      throw new Error('Downloaded model file is incomplete. Retry to resume the remaining bytes.');
    }
    onProgress?.(100);
    return completedPath;
  } catch (error) {
    // Do not delete .part: a subsequent attempt resumes from its current size.
    throw error;
  }
}
