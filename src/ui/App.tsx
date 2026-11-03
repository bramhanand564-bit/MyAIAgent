import React, { useEffect, useState } from 'react';
import {
  SafeAreaView,
  StatusBar,
  Text,
  Pressable,
  View,
  StyleSheet,
} from 'react-native';
import ChatScreen from './screens/ChatScreen';
import SkillStoreScreen from './screens/SkillStoreScreen';
import KnowledgeVaultScreen from './screens/KnowledgeVaultScreen';
import CronDashboardScreen from './screens/CronDashboardScreen';
import { getDatabase } from '../reader/sqliteFtsDatabase';
import { loadSkillPreferences } from '../skills/skillRegistry';
import { attachModelLifecycleCleanup } from '../brain/llamaService';
import HeadlessBrowserController from '../rpa/HeadlessBrowserController';
import { useAgentStore } from '../state/useAgentStore';

type Tab = 'chat' | 'skills' | 'vault' | 'cron';

export default function AgentApp() {
  const [tab, setTab] = useState<Tab>('chat');
  const [ready, setReady] = useState(false);
  const autonomousExecutionMode = useAgentStore((state) => state.autonomousExecutionMode);

  useEffect(() => {
    let cleanupModel: () => void = () => {};
    void Promise.all([getDatabase(), loadSkillPreferences()])
      .then(() => setReady(true))
      .catch(() => setReady(true));
    cleanupModel = attachModelLifecycleCleanup();
    return () => cleanupModel();
  }, []);

  const content = (() => {
    if (tab === 'skills') return <SkillStoreScreen />;
    if (tab === 'vault') return <KnowledgeVaultScreen />;
    if (tab === 'cron') return <CronDashboardScreen />;
    return <ChatScreen />;
  })();

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0B1220" />
      <View style={styles.header}>
        <View>
          <Text style={styles.brand}>LOCAL AI AGENT</Text>
          <Text style={styles.subtitle}>{ready ? 'On-device + web retrieval' : 'Starting local services…'}</Text>
        </View>
        <View style={styles.dot} />
      </View>
      <View style={styles.content}>{content}</View>
      <View style={styles.tabs}>
        {([
          ['chat', 'Chat'],
          ['skills', 'Skills'],
          ['vault', 'Knowledge'],
          ['cron', 'Scheduler'],
        ] as Array<[Tab, string]>).map(([key, label]) => (
          <Pressable
            key={key}
            onPress={() => setTab(key)}
            style={[styles.tab, tab === key && styles.tabActive]}
          >
            <Text style={[styles.tabText, tab === key && styles.tabTextActive]}>{label}</Text>
          </Pressable>
        ))}
      </View>
      <HeadlessBrowserController visible={false} automationEnabled={autonomousExecutionMode} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0B1220' },
  header: { minHeight: 64, paddingHorizontal: 16, paddingVertical: 10, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  brand: { color: '#FFFFFF', fontSize: 17, fontWeight: '800', letterSpacing: 1.2 },
  subtitle: { color: '#94A3B8', fontSize: 12, marginTop: 3 },
  dot: { width: 10, height: 10, borderRadius: 5, backgroundColor: '#22C55E' },
  content: { flex: 1 },
  tabs: { flexDirection: 'row', padding: 8, backgroundColor: '#111C2E', gap: 6 },
  tab: { flex: 1, paddingVertical: 12, borderRadius: 10, alignItems: 'center' },
  tabActive: { backgroundColor: '#2563EB' },
  tabText: { color: '#CBD5E1', fontSize: 12, fontWeight: '600' },
  tabTextActive: { color: '#FFFFFF' },
});
