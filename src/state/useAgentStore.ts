import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import {
  isLlamaLoaded,
  loadLocalModel,
} from '../brain/llamaService';
import { processUserMessage } from '../brain/agentOrchestrator';
import {
  executeApprovedSkill,
} from '../skills/skillExecutor';
import type { PendingPhoneAction } from '../skills/packs/phoneController';

const AUTONOMOUS_MODE_KEY = 'local-agent-autonomous-execution-v1';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  sources?: string[];
}

interface AgentState {
  messages: ChatMessage[];
  isBusy: boolean;
  isModelLoaded: boolean;
  modelProgress: number;
  pendingAction: PendingPhoneAction | null;
  autonomousExecutionMode: boolean;
  setAutonomousExecutionMode: (enabled: boolean) => Promise<void>;
  sendMessage: (text: string) => Promise<void>;
  confirmPendingAction: () => Promise<void>;
  dismissPendingAction: () => void;
  downloadLocalModel: () => Promise<void>;
}

function makeMessage(role: 'user' | 'assistant', text: string, sources?: string[]): ChatMessage {
  const base: ChatMessage = {
    id: Date.now().toString(36) + Math.random().toString(36).slice(2, 7),
    role,
    text,
  };
  return sources ? { ...base, sources } : base;
}

export const useAgentStore = create<AgentState>((set, get) => ({
  messages: [
    makeMessage('assistant', 'Namaste! Main aapka local-first AI Agent hoon. Web search, PDF/notes retrieval aur confirmed phone actions available hain.'),
  ],
  isBusy: false,
  isModelLoaded: isLlamaLoaded(),
  modelProgress: 0,
  pendingAction: null,
  autonomousExecutionMode: false,

  setAutonomousExecutionMode: async (enabled: boolean) => {
    set({ autonomousExecutionMode: enabled, pendingAction: null });
    try {
      await AsyncStorage.setItem(AUTONOMOUS_MODE_KEY, enabled ? 'true' : 'false');
    } catch {
      set({ autonomousExecutionMode: false });
      throw new Error('Autonomous mode setting could not be saved. Storage permission or space may be unavailable.');
    }
  },

  sendMessage: async (text: string) => {
    const value = text.trim();
    if (!value || get().isBusy) return;
    const autonomous = get().autonomousExecutionMode;
    set((state) => ({
      isBusy: true,
      pendingAction: null,
      messages: [...state.messages, makeMessage('user', value)],
    }));
    try {
      const result = await processUserMessage(value, autonomous);
      set((state) => ({
        messages: [...state.messages, makeMessage('assistant', result.text, result.sources)],
        pendingAction: result.pendingAction ?? null,
      }));
    } catch (error) {
      set((state) => ({
        messages: [...state.messages, makeMessage(
          'assistant',
          error instanceof Error ? error.message : 'Task complete nahi ho saka.',
        )],
      }));
    } finally {
      set({ isBusy: false });
    }
  },

  confirmPendingAction: async () => {
    const action = get().pendingAction;
    if (!action || get().isBusy) return;
    set({ isBusy: true, pendingAction: null });
    try {
      const result = await executeApprovedSkill('phone_controller', action);
      set((state) => ({
        messages: [...state.messages, makeMessage('assistant', result.message)],
      }));
    } catch (error) {
      set((state) => ({
        messages: [...state.messages, makeMessage(
          'assistant',
          error instanceof Error ? error.message : 'Phone action failed.',
        )],
      }));
    } finally {
      set({ isBusy: false });
    }
  },

  dismissPendingAction: () => set({ pendingAction: null }),

  downloadLocalModel: async () => {
    if (get().isBusy || isLlamaLoaded()) return;
    set({ isBusy: true, modelProgress: 0 });
    try {
      await loadLocalModel((percent) => set({ modelProgress: percent }));
      set({ isModelLoaded: true, modelProgress: 100 });
      set((state) => ({
        messages: [...state.messages, makeMessage('assistant', 'Local model loaded. Grounded answers can now be summarized on-device.')],
      }));
    } catch (error) {
      set((state) => ({
        messages: [...state.messages, makeMessage(
          'assistant',
          'Model load/download failed: ' + (error instanceof Error ? error.message : 'unknown error'),
        )],
      }));
    } finally {
      set({ isBusy: false });
    }
  },
}));

void AsyncStorage.getItem(AUTONOMOUS_MODE_KEY)
  .then((stored) => {
    useAgentStore.setState({ autonomousExecutionMode: stored === 'true' });
  })
  .catch(() => {
    useAgentStore.setState({ autonomousExecutionMode: false });
  });
