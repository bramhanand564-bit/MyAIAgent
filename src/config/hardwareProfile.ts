import DeviceInfo from 'react-native-device-info';

export type ModelId = 'qwen2.5-0.5b-q4km' | 'qwen2.5-1.5b-q4km';

export interface HardwareProfile {
  totalMemoryBytes: number;
  totalMemoryGb: number;
  tier: 'low' | 'standard' | 'high';
  modelId: ModelId;
  contextSize: 1024 | 1536 | 2048;
  predictionTokens: 96 | 128 | 160;
  gpuLayers: 0;
  explanation: string;
}

export async function getHardwareProfile(): Promise<HardwareProfile> {
  let totalMemoryBytes = 0;
  try {
    totalMemoryBytes = await DeviceInfo.getTotalMemory();
  } catch {
    totalMemoryBytes = 0;
  }

  const totalMemoryGb = totalMemoryBytes > 0
    ? totalMemoryBytes / (1024 ** 3)
    : 4;
  if (totalMemoryGb < 5.2) {
    return {
      totalMemoryBytes,
      totalMemoryGb,
      tier: 'low',
      modelId: 'qwen2.5-0.5b-q4km',
      contextSize: 1024,
      predictionTokens: 96,
      gpuLayers: 0,
      explanation: 'Low-memory profile: smaller 0.5B model, short context, CPU-only inference.',
    };
  }
  if (totalMemoryGb < 7.5) {
    return {
      totalMemoryBytes,
      totalMemoryGb,
      tier: 'standard',
      modelId: 'qwen2.5-1.5b-q4km',
      contextSize: 1536,
      predictionTokens: 128,
      gpuLayers: 0,
      explanation: 'Standard profile: 1.5B model with a conservative context window.',
    };
  }
  return {
    totalMemoryBytes,
    totalMemoryGb,
    tier: 'high',
    modelId: 'qwen2.5-1.5b-q4km',
    contextSize: 2048,
    predictionTokens: 160,
    gpuLayers: 0,
    explanation: 'Higher-memory profile: 1.5B model with a slightly larger context window.',
  };
}
