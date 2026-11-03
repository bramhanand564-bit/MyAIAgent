import { Linking, Platform } from 'react-native';

export type PendingPhoneAction =
  | { action: 'call'; phone: string; summary: string }
  | { action: 'whatsapp'; phone: string; message: string; summary: string }
  | { action: 'set_alarm'; hour: number; minute: number; summary: string };

export type PhoneIntentParse =
  | PendingPhoneAction
  | { error: string }
  | null;

function extractPhoneNumber(text: string): string | null {
  const matches = text.match(/\+?[0-9][0-9\s().-]{7,}[0-9]/g) ?? [];
  for (const candidate of matches) {
    const digits = candidate.replace(/[^0-9]/g, '');
    if (digits.length >= 10 && digits.length <= 15) return digits;
  }
  return null;
}

function parseAlarmTime(text: string): { hour: number; minute: number } | null {
  const match = text.match(/\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b/i);
  if (!match) return null;
  let hour = Number(match[1]);
  const minute = Number(match[2] || '0');
  const meridiem = (match[3] || '').toLowerCase();
  if (minute > 59 || hour > 23 || hour < 0) return null;
  if (meridiem === 'pm' && hour < 12) hour += 12;
  if (meridiem === 'am' && hour === 12) hour = 0;
  return { hour, minute };
}

export function parsePhoneIntent(text: string): PhoneIntentParse {
  const value = text.trim();
  const lower = value.toLowerCase();

  if (/\b(alarm|wake me|remind me)\b|अलार्म|जगा दो|रिमाइंडर/.test(lower)) {
    const time = parseAlarmTime(value);
    if (!time) return { error: 'Alarm ka time HH:MM format mein likhiye, jaise “7:30 AM alarm”.' };
    return {
      action: 'set_alarm',
      hour: time.hour,
      minute: time.minute,
      summary: 'Alarm at ' + String(time.hour).padStart(2, '0') + ':' + String(time.minute).padStart(2, '0'),
    };
  }

  if (/\b(whatsapp|message|send msg|text)\b|व्हाट्सऐप|मैसेज|संदेश/.test(lower)) {
    const phone = extractPhoneNumber(value);
    if (!phone) return { error: 'WhatsApp ke liye country code ke saath phone number likhiye. App contacts ko bina permission read nahi karta.' };
    const messageMatch = value.match(/(?:message|msg|text|मैसेज|संदेश|likho|लिखो)\s*[:,-]?\s*(.+)$/i);
    const message = messageMatch?.[1]?.trim() || 'Hello';
    return {
      action: 'whatsapp',
      phone,
      message,
      summary: 'WhatsApp composer for +' + phone + ' with a pre-filled message',
    };
  }

  if (/\b(call|dial|phone)\b|कॉल|फोन/.test(lower)) {
    const phone = extractPhoneNumber(value);
    if (!phone) return { error: 'Call ke liye phone number likhiye. Abhi contact-name lookup enabled nahi hai.' };
    return {
      action: 'call',
      phone,
      summary: 'Open dialer for +' + phone,
    };
  }
  return null;
}

export async function executePhoneAction(action: PendingPhoneAction): Promise<string> {
  if (Platform.OS !== 'android') {
    throw new Error('Phone actions are supported only on Android.');
  }
  if (action.action === 'call') {
    await Linking.openURL('tel:+' + action.phone);
    return 'Dialer opened for +' + action.phone + '. Call place karne se pehle phone screen par confirm kijiye.';
  }
  if (action.action === 'whatsapp') {
    const url = 'https://wa.me/' + action.phone + '?text=' + encodeURIComponent(action.message);
    await Linking.openURL(url);
    return 'WhatsApp opened with the message pre-filled. Message aapko khud send karna hoga.';
  }

  await Linking.sendIntent('android.intent.action.SET_ALARM', [
    { key: 'android.intent.extra.alarm.HOUR', value: action.hour },
    { key: 'android.intent.extra.alarm.MINUTES', value: action.minute },
    { key: 'android.intent.extra.alarm.MESSAGE', value: 'Local AI Agent reminder' },
    { key: 'android.intent.extra.alarm.SKIP_UI', value: false },
  ]);
  return action.summary + ' clock app ko bheja gaya. Alarm create hua ya nahi, clock app mein verify kijiye.';
}
