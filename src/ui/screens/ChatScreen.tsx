import React, { useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { useAgentStore } from '../../state/useAgentStore';

export default function ChatScreen() {
  const [input, setInput] = useState('');
  const messages = useAgentStore((state) => state.messages);
  const autonomousExecutionMode = useAgentStore((state) => state.autonomousExecutionMode);
  const setAutonomousExecutionMode = useAgentStore((state) => state.setAutonomousExecutionMode);
  const isBusy = useAgentStore((state) => state.isBusy);
  const isModelLoaded = useAgentStore((state) => state.isModelLoaded);
  const modelProgress = useAgentStore((state) => state.modelProgress);
  const pendingAction = useAgentStore((state) => state.pendingAction);
  const sendMessage = useAgentStore((state) => state.sendMessage);
  const confirmPendingAction = useAgentStore((state) => state.confirmPendingAction);
  const dismissPendingAction = useAgentStore((state) => state.dismissPendingAction);
  const downloadLocalModel = useAgentStore((state) => state.downloadLocalModel);

  async function send(): Promise<void> {
    const value = input.trim();
    if (!value || isBusy) return;
    setInput('');
    await sendMessage(value);
  }

  return (
    <View style={styles.container}>
      <View style={styles.topRow}>
        <View>
          <Text style={styles.heading}>Agent Chat</Text>
          <Text style={styles.caption}>{isModelLoaded ? 'Local model loaded' : 'Rule router + online retrieval'}</Text>
        </View>
        {!isModelLoaded && (
          <Pressable
            style={[styles.smallButton, isBusy && styles.disabled]}
            disabled={isBusy}
            onPress={() => void downloadLocalModel()}
          >
            <Text style={styles.smallButtonText}>Download local brain</Text>
          </Pressable>
        )}
      </View>

      <View style={styles.autonomousCard}>
        <View style={styles.autonomousTextBox}>
          <Text style={styles.autonomousTitle}>User-authorized autonomous mode</Text>
          <Text style={styles.autonomousCaption}>When enabled, phone intents open directly without the extra in-app confirmation. Android may still show its own dialer, alarm or app confirmation. WhatsApp messages are never silently sent.</Text>
        </View>
        <Switch
          value={autonomousExecutionMode}
          onValueChange={(value) => {
            void setAutonomousExecutionMode(value).catch(() => undefined);
          }}
          trackColor={{ false: '#475569', true: '#2563EB' }}
          thumbColor={autonomousExecutionMode ? '#FFFFFF' : '#CBD5E1'}
        />
      </View>

      {modelProgress > 0 && modelProgress < 100 && (
        <View style={styles.progressOuter}>
          <View style={[styles.progressInner, { width: modelProgress + '%' }]} />
          <Text style={styles.progressText}>{modelProgress}%</Text>
        </View>
      )}

      <ScrollView style={styles.messages} contentContainerStyle={styles.messagesContent}>
        {messages.map((message) => (
          <View
            key={message.id}
            style={[styles.bubble, message.role === 'user' ? styles.userBubble : styles.assistantBubble]}
          >
            <Text style={styles.role}>{message.role === 'user' ? 'YOU' : 'AGENT'}</Text>
            <Text style={styles.messageText}>{message.text}</Text>
            {message.sources?.map((source, index) => (
              <Text key={source + index} style={styles.sourceText}>Source: {source}</Text>
            ))}
          </View>
        ))}
        {isBusy && (
          <View style={styles.busyRow}>
            <ActivityIndicator color="#60A5FA" />
            <Text style={styles.busyText}>Working…</Text>
          </View>
        )}
      </ScrollView>

      {pendingAction && (
        <View style={styles.confirmBox}>
          <Text style={styles.confirmText}>Permission required: {pendingAction.summary}</Text>
          <View style={styles.confirmRow}>
            <Pressable style={styles.confirmButton} onPress={() => void confirmPendingAction()}>
              <Text style={styles.confirmButtonText}>Confirm and open</Text>
            </Pressable>
            <Pressable style={styles.cancelButton} onPress={dismissPendingAction}>
              <Text style={styles.cancelButtonText}>Cancel</Text>
            </Pressable>
          </View>
        </View>
      )}

      <View style={styles.composer}>
        <TextInput
          value={input}
          onChangeText={setInput}
          placeholder="Hindi / English / Hinglish mein likhiye…"
          placeholderTextColor="#94A3B8"
          multiline
          style={styles.input}
          editable={!isBusy}
        />
        <Pressable style={[styles.sendButton, isBusy && styles.disabled]} disabled={isBusy} onPress={() => void send()}>
          <Text style={styles.sendText}>Send</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 12, gap: 8, backgroundColor: '#0B1220' },
  topRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  heading: { color: '#FFFFFF', fontSize: 19, fontWeight: '800' },
  caption: { color: '#94A3B8', fontSize: 11, marginTop: 2 },
  smallButton: { backgroundColor: '#1D4ED8', paddingHorizontal: 10, paddingVertical: 9, borderRadius: 8 },
  smallButtonText: { color: '#FFFFFF', fontSize: 11, fontWeight: '700' },
  autonomousCard: { flexDirection: 'row', alignItems: 'center', gap: 10, padding: 10, borderRadius: 10, backgroundColor: '#172338', borderWidth: 1, borderColor: '#334155' },
  autonomousTextBox: { flex: 1, gap: 3 },
  autonomousTitle: { color: '#FFFFFF', fontSize: 12, fontWeight: '700' },
  autonomousCaption: { color: '#94A3B8', fontSize: 10, lineHeight: 14 },
  progressOuter: { height: 18, borderRadius: 8, backgroundColor: '#1E293B', overflow: 'hidden', justifyContent: 'center' },
  progressInner: { height: 18, backgroundColor: '#2563EB' },
  progressText: { position: 'absolute', alignSelf: 'center', color: '#FFFFFF', fontSize: 10 },
  messages: { flex: 1 },
  messagesContent: { gap: 10, paddingVertical: 8 },
  bubble: { borderRadius: 12, padding: 12, maxWidth: '94%' },
  userBubble: { alignSelf: 'flex-end', backgroundColor: '#1D4ED8' },
  assistantBubble: { alignSelf: 'flex-start', backgroundColor: '#172338', borderWidth: 1, borderColor: '#26364F' },
  role: { color: '#93C5FD', fontSize: 9, fontWeight: '800', letterSpacing: 1, marginBottom: 5 },
  messageText: { color: '#F8FAFC', lineHeight: 21, fontSize: 14 },
  sourceText: { color: '#93C5FD', fontSize: 10, marginTop: 6 },
  busyRow: { flexDirection: 'row', gap: 8, alignItems: 'center', padding: 8 },
  busyText: { color: '#CBD5E1', fontSize: 12 },
  confirmBox: { padding: 10, backgroundColor: '#422006', borderRadius: 10, borderColor: '#A16207', borderWidth: 1, gap: 8 },
  confirmText: { color: '#FEF3C7', fontSize: 12 },
  confirmRow: { flexDirection: 'row', gap: 8 },
  confirmButton: { flex: 1, padding: 10, backgroundColor: '#15803D', borderRadius: 8, alignItems: 'center' },
  confirmButtonText: { color: '#FFFFFF', fontWeight: '700' },
  cancelButton: { padding: 10, backgroundColor: '#334155', borderRadius: 8, alignItems: 'center' },
  cancelButtonText: { color: '#FFFFFF', fontWeight: '700' },
  composer: { flexDirection: 'row', alignItems: 'flex-end', gap: 8, backgroundColor: '#111C2E', padding: 8, borderRadius: 12 },
  input: { flex: 1, minHeight: 42, maxHeight: 110, color: '#FFFFFF', paddingHorizontal: 8, paddingVertical: 6 },
  sendButton: { backgroundColor: '#2563EB', borderRadius: 8, paddingHorizontal: 16, paddingVertical: 12 },
  sendText: { color: '#FFFFFF', fontWeight: '800' },
  disabled: { opacity: 0.5 },
});
