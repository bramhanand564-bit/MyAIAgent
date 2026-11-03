import React, { useEffect, useMemo, useRef, useState } from 'react';
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
  automationEnabled?: boolean;
  initialUrl?: string;
  automationScript?: string;
  onExtract?: (page: ExtractedPage) => void;
}

export default function HeadlessBrowserController({
  visible,
  automationEnabled = false,
  initialUrl = 'about:blank',
  automationScript,
  onExtract,
}: Props) {
  const [urlText, setUrlText] = useState(initialUrl);
  const [activeUrl, setActiveUrl] = useState(initialUrl);
  const webViewRef = useRef<WebView>(null);
  const loadedRef = useRef(false);
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

  const shouldRender = visible || automationEnabled;

  useEffect(() => {
    if (!automationScript || !loadedRef.current || !automationEnabled) return;
    webViewRef.current?.injectJavaScript(
      automationScript + '\ntrue;',
    );
  }, [automationScript, automationEnabled]);

  if (!shouldRender) return null;

  function handleMessage(event: WebViewMessageEvent): void {
    try {
      const payload = JSON.parse(event.nativeEvent.data) as ExtractedPage;
      onExtract?.(payload);
    } catch {
      onExtract?.({ error: 'The page returned invalid extraction data.' });
    }
  }

  return (
    <View style={[styles.container, !visible && styles.offscreenContainer]} pointerEvents={visible ? 'auto' : 'none'}>
      {visible ? (
        <>
          <Text style={styles.heading}>Web reader / automation</Text>
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
                if (validUrl) {
                  loadedRef.current = false;
                  setActiveUrl(validUrl);
                }
              }}
            >
              <Text style={styles.buttonText}>Open</Text>
            </Pressable>
          </View>
        </>
      ) : null}
      <WebView
        ref={webViewRef}
        source={{ uri: activeUrl }}
        javaScriptEnabled
        domStorageEnabled
        sharedCookiesEnabled
        thirdPartyCookiesEnabled
        injectedJavaScript={EXTRACT_PUBLIC_PAGE_SCRIPT}
        onLoadEnd={() => {
          loadedRef.current = true;
          if (automationEnabled && automationScript) {
            webViewRef.current?.injectJavaScript(automationScript + '\ntrue;');
          }
        }}
        onMessage={handleMessage}
        style={[styles.webview, !visible && styles.offscreenWebView]}
      />
      {visible ? (
        <Text style={styles.hint}>Only public page text is extracted. Site credentials and password fields are not read or submitted by this reader.</Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, gap: 8, minHeight: 400, backgroundColor: '#101827', padding: 10 },
  offscreenContainer: { position: 'absolute', left: 0, bottom: 0, width: 1, height: 1, minHeight: 1, padding: 0, opacity: 0.01, overflow: 'hidden' },
  heading: { color: '#FFFFFF', fontSize: 16, fontWeight: '700' },
  row: { flexDirection: 'row', gap: 8 },
  input: { flex: 1, backgroundColor: '#FFFFFF', borderRadius: 8, paddingHorizontal: 10, color: '#111827' },
  button: { backgroundColor: '#2563EB', borderRadius: 8, paddingHorizontal: 14, justifyContent: 'center' },
  buttonText: { color: '#FFFFFF', fontWeight: '600', textAlign: 'center' },
  webview: { flex: 1, minHeight: 300, backgroundColor: '#FFFFFF' },
  offscreenWebView: { width: 1, height: 1, minHeight: 1, opacity: 0.01 },
  hint: { color: '#94A3B8', fontSize: 10, lineHeight: 15 },
});
