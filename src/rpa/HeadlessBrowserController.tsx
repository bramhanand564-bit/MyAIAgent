import React, { useMemo, useState } from 'react';
import { Pressable, Text, TextInput, View, StyleSheet } from 'react-native';
import WebView, { type WebViewMessageEvent } from 'react-native-webview';
import { EXTRACT_PUBLIC_PAGE_SCRIPT } from './rpaScripts';

export interface ExtractedPage {
  title?: string;
  description?: string;
  url?: string;
  text?: string;
  error?: string;
}

interface Props {
  visible: boolean;
  initialUrl?: string;
  onExtract?: (page: ExtractedPage) => void;
}

export default function HeadlessBrowserController({
  visible,
  initialUrl = 'https://duckduckgo.com/',
  onExtract,
}: Props) {
  const [urlText, setUrlText] = useState(initialUrl);
  const [activeUrl, setActiveUrl] = useState(initialUrl);
  const validUrl = useMemo(() => {
    const value = urlText.trim();
    if (!/^https?:\/\//i.test(value)) return null;
    try {
      const parsed = new URL(value);
      return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : null;
    } catch {
      return null;
    }
  }, [urlText]);

  if (!visible) return null;

  function handleMessage(event: WebViewMessageEvent): void {
    try {
      const payload = JSON.parse(event.nativeEvent.data) as ExtractedPage;
      onExtract?.(payload);
    } catch {
      onExtract?.({ error: 'The page returned invalid extraction data.' });
    }
  }

  return (
    <View style={styles.container}>
      <Text style={styles.heading}>User-visible web reader</Text>
      <View style={styles.row}>
        <TextInput
          value={urlText}
          onChangeText={setUrlText}
          autoCapitalize="none"
          keyboardType="url"
          placeholder="https://example.com"
          style={styles.input}
        />
        <Pressable
          style={styles.button}
          onPress={() => {
            if (validUrl) setActiveUrl(validUrl);
          }}
        >
          <Text style={styles.buttonText}>Open</Text>
        </Pressable>
      </View>
      <WebView
        source={{ uri: activeUrl }}
        javaScriptEnabled
        domStorageEnabled
        sharedCookiesEnabled
        injectedJavaScript={EXTRACT_PUBLIC_PAGE_SCRIPT}
        onMessage={handleMessage}
        style={styles.webview}
      />
      <Text style={styles.hint}>The visible page title, URL and public body text are sent to the app after each page load. Login forms and passwords are not extracted.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, gap: 8, minHeight: 400, backgroundColor: '#101827', padding: 10 },
  heading: { color: '#FFFFFF', fontSize: 16, fontWeight: '700' },
  row: { flexDirection: 'row', gap: 8 },
  input: { flex: 1, backgroundColor: '#FFFFFF', borderRadius: 8, paddingHorizontal: 10, color: '#111827' },
  button: { backgroundColor: '#2563EB', borderRadius: 8, paddingHorizontal: 14, justifyContent: 'center' },
  buttonText: { color: '#FFFFFF', fontWeight: '600', textAlign: 'center' },
  webview: { flex: 1, minHeight: 300, backgroundColor: '#FFFFFF' },
  hint: { color: '#94A3B8', fontSize: 10, lineHeight: 15 },
});
