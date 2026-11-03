import React, { useEffect, useState } from 'react';
import { getPublishingWebhook, setPublishingWebhook } from '../../skills/packs/dailyBriefingCron';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  getCronJobs,
  type CronJobRecord,
} from '../../reader/sqliteFtsDatabase';
import {
  createDailyBriefingJob,
  isSchedulerRunning,
  startScheduler,
  stopScheduler,
  requestBatteryOptimizationExemption,
  requestExactAlarmAccess,
} from '../../cron/schedulerService';

export default function CronDashboardScreen() {
  const [time, setTime] = useState('08:00');
  const [topic, setTopic] = useState('India technology and business');
  const [jobs, setJobs] = useState<CronJobRecord[]>([]);
  const [running, setRunning] = useState(isSchedulerRunning());
  const [message, setMessage] = useState('');
  const [webhookUrl, setWebhookUrl] = useState('');

  async function refresh(): Promise<void> {
    setJobs(await getCronJobs());
    setRunning(isSchedulerRunning());
  }

  useEffect(() => {
    void refresh();
    void getPublishingWebhook().then(setWebhookUrl).catch(() => undefined);
  }, []);

  async function saveWebhook(): Promise<void> {
    try {
      await setPublishingWebhook(webhookUrl);
      setMessage(webhookUrl.trim() ? 'HTTPS publishing webhook saved.' : 'Publishing webhook cleared.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Webhook could not be saved.');
    }
  }

  async function addJob(): Promise<void> {
    try {
      await createDailyBriefingJob(time, topic);
      setMessage('Daily briefing saved.');
      await refresh();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not save the job.');
    }
  }

  async function openBatterySettings(): Promise<void> {
    try {
      const alreadyAllowed = await requestBatteryOptimizationExemption();
      setMessage(alreadyAllowed ? 'Battery optimization exemption is already allowed.' : 'Battery settings opened. Approve the exemption if Android offers it, then return here.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not open battery settings.');
    }
  }

  async function openExactAlarmSettings(): Promise<void> {
    try {
      const alreadyAllowed = await requestExactAlarmAccess();
      setMessage(alreadyAllowed ? 'Exact alarm access is already allowed.' : 'Exact alarm settings opened. Grant access if you want exact-alarm features.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not open exact alarm settings.');
    }
  }

  async function toggleService(): Promise<void> {
    try {
      if (running) await stopScheduler();
      else await startScheduler();
      setRunning(isSchedulerRunning());
      setMessage(running ? 'Scheduler stopped.' : 'Scheduler started. Keep its foreground notification visible.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Scheduler could not start.');
    }
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.heading}>Scheduler</Text>
      <Text style={styles.caption}>Best-effort Android foreground service. Android may stop background tasks or restrict long-running services; this is not a guaranteed 24×7 cron daemon.</Text>

      <View style={styles.card}>
        <Text style={styles.label}>Daily briefing time (24-hour HH:MM)</Text>
        <TextInput value={time} onChangeText={setTime} style={styles.input} placeholder="08:00" placeholderTextColor="#64748B" />
        <Text style={styles.label}>Topic</Text>
        <TextInput value={topic} onChangeText={setTopic} style={styles.input} placeholder="Technology and business" placeholderTextColor="#64748B" />
        <Text style={styles.label}>Optional HTTPS publishing webhook</Text>
        <TextInput
          value={webhookUrl}
          onChangeText={setWebhookUrl}
          autoCapitalize="none"
          keyboardType="url"
          style={styles.input}
          placeholder="https://your-server.example/publish"
          placeholderTextColor="#64748B"
        />
        <Text style={styles.caption}>When User-authorized autonomous mode is ON, a generated briefing is POSTed as JSON to this endpoint. Configure authentication and platform OAuth on your own trusted webhook server; this app does not store OAuth secrets.</Text>
        <Pressable style={styles.secondaryButton} onPress={() => void saveWebhook()}>
          <Text style={styles.buttonText}>Save webhook</Text>
        </Pressable>
        <Pressable style={styles.primaryButton} onPress={() => void addJob()}>
          <Text style={styles.buttonText}>Save daily briefing</Text>
        </Pressable>
      </View>

      <View style={styles.settingsRow}>
        <Pressable style={styles.settingsButton} onPress={() => void openBatterySettings()}>
          <Text style={styles.buttonText}>Battery exemption</Text>
        </Pressable>
        <Pressable style={styles.settingsButton} onPress={() => void openExactAlarmSettings()}>
          <Text style={styles.buttonText}>Exact alarm access</Text>
        </Pressable>
      </View>

      <Pressable style={[styles.serviceButton, running && styles.stopButton]} onPress={() => void toggleService()}>
        <Text style={styles.buttonText}>{running ? 'Stop foreground scheduler' : 'Start foreground scheduler'}</Text>
      </Pressable>
      {message ? <Text style={styles.message}>{message}</Text> : null}

      <Text style={styles.sectionTitle}>Saved jobs ({jobs.length})</Text>
      {jobs.map((job) => (
        <View key={job.jobId} style={styles.jobCard}>
          <Text style={styles.jobTitle}>{job.title}</Text>
          <Text style={styles.jobMeta}>{job.cronExpression} · {job.isActive ? 'Enabled' : 'Disabled'}</Text>
          <Text style={styles.jobMeta}>{job.skillId}</Text>
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B1220' },
  content: { padding: 14, gap: 10 },
  heading: { color: '#FFFFFF', fontSize: 20, fontWeight: '800' },
  caption: { color: '#94A3B8', fontSize: 12, lineHeight: 18 },
  card: { backgroundColor: '#172338', borderRadius: 12, padding: 14, gap: 8 },
  label: { color: '#CBD5E1', fontSize: 12, fontWeight: '600' },
  input: { minHeight: 42, color: '#FFFFFF', backgroundColor: '#0B1220', borderRadius: 8, paddingHorizontal: 10 },
  secondaryButton: { backgroundColor: '#475569', borderRadius: 8, padding: 11, alignItems: 'center' },
  primaryButton: { backgroundColor: '#2563EB', borderRadius: 8, padding: 12, alignItems: 'center', marginTop: 4 },
  settingsRow: { flexDirection: 'row', gap: 8 },
  settingsButton: { flex: 1, backgroundColor: '#334155', padding: 11, borderRadius: 9, alignItems: 'center' },
  serviceButton: { backgroundColor: '#15803D', padding: 13, borderRadius: 9, alignItems: 'center' },
  stopButton: { backgroundColor: '#B91C1C' },
  buttonText: { color: '#FFFFFF', fontSize: 12, fontWeight: '700' },
  message: { color: '#FDE68A', fontSize: 12 },
  sectionTitle: { color: '#E2E8F0', fontSize: 15, fontWeight: '700', marginTop: 8 },
  jobCard: { backgroundColor: '#172338', padding: 11, borderRadius: 8, gap: 4 },
  jobTitle: { color: '#FFFFFF', fontSize: 13, fontWeight: '700' },
  jobMeta: { color: '#94A3B8', fontSize: 10 },
});
