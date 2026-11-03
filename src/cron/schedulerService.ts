import BackgroundService from 'react-native-background-actions';
import { NativeModules, PermissionsAndroid, Platform } from 'react-native';
import {
  getCronJobs,
  saveCronJob,
  updateCronLastRun,
} from '../reader/sqliteFtsDatabase';
import { isAutonomousExecutionEnabled, runDailyBriefing } from '../skills/packs/dailyBriefingCron';


interface AgentSystemSettings {
  requestBatteryOptimizationExemption(): Promise<boolean>;
  requestExactAlarmAccess(): Promise<boolean>;
}

const systemSettings = NativeModules.AgentSystemSettings as AgentSystemSettings | undefined;

export async function requestBatteryOptimizationExemption(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  if (!systemSettings) throw new Error('Android system settings helper is missing from this APK.');
  return systemSettings.requestBatteryOptimizationExemption();
}

export async function requestExactAlarmAccess(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  if (!systemSettings) throw new Error('Android system settings helper is missing from this APK.');
  return systemSettings.requestExactAlarmAccess();
}

const SERVICE_DELAY_MS = 30_000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function requestNotificationPermission(): Promise<void> {
  if (Platform.OS !== 'android' || Number(Platform.Version) < 33) return;
  const permission = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS;
  const result = await PermissionsAndroid.request(permission);
  if (result !== PermissionsAndroid.RESULTS.GRANTED) {
    throw new Error('Allow notifications to start the visible foreground scheduler.');
  }
}

async function runDueJobs(): Promise<void> {
  const jobs = await getCronJobs();
  const now = new Date();
  const todayKey = now.getFullYear() + '-' + now.getMonth() + '-' + now.getDate();
  const currentMinutes = now.getHours() * 60 + now.getMinutes();

  for (const job of jobs) {
    if (!job.isActive || job.skillId !== 'daily_briefing') continue;
    const match = job.cronExpression.match(/^DAILY@(\d{2}):(\d{2})$/);
    if (!match) continue;
    const targetMinutes = Number(match[1]) * 60 + Number(match[2]);
    const last = job.lastRun > 0 ? new Date(job.lastRun) : null;
    const lastKey = last
      ? last.getFullYear() + '-' + last.getMonth() + '-' + last.getDate()
      : '';
    if (currentMinutes < targetMinutes || lastKey === todayKey) continue;

    await updateCronLastRun(job.jobId, Date.now());
    let topic = 'India technology and business';
    try {
      const parameters = JSON.parse(job.paramsJson) as { topic?: string };
      if (parameters.topic) topic = parameters.topic;
    } catch {
      // Use the default topic for malformed stored parameters.
    }
    await runDailyBriefing(topic, await isAutonomousExecutionEnabled());
  }
}

async function schedulerTask(): Promise<void> {
  while (BackgroundService.isRunning()) {
    try {
      await runDueJobs();
    } catch {
      // One failed job must not terminate the scheduler loop.
    }
    await sleep(SERVICE_DELAY_MS);
  }
}

export async function createDailyBriefingJob(time: string, topic: string): Promise<void> {
  const match = time.match(/^(\d{2}):(\d{2})$/);
  if (!match) throw new Error('Time must use HH:MM format.');
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) throw new Error('Please enter a valid 24-hour time.');
  await saveCronJob({
    jobId: 'daily_briefing_' + hour.toString().padStart(2, '0') + minute.toString().padStart(2, '0'),
    title: 'Daily briefing at ' + time,
    cronExpression: 'DAILY@' + time,
    skillId: 'daily_briefing',
    paramsJson: JSON.stringify({ topic }),
    isActive: true,
    lastRun: 0,
  });
}

export async function startScheduler(): Promise<void> {
  if (BackgroundService.isRunning()) return;
  await requestNotificationPermission();
  await BackgroundService.start(schedulerTask, {
    taskName: 'LocalAIAgentScheduler',
    taskTitle: 'Local AI Agent scheduler is active',
    taskDesc: 'Checking your enabled daily briefing jobs.',
    taskIcon: { name: 'ic_launcher', type: 'mipmap' },
    color: '#2563EB',
    linkingURI: 'localaiaagent://scheduler',
    parameters: {},
    foregroundServiceType: ['dataSync'],
  } as never);
}

export async function stopScheduler(): Promise<void> {
  if (BackgroundService.isRunning()) await BackgroundService.stop();
}

export function isSchedulerRunning(): boolean {
  return BackgroundService.isRunning();
}
