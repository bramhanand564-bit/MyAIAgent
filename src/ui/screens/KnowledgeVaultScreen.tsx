import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  indexWebPage,
  pickAndIndexDocument,
} from '../../reader/documentIngestor';
import {
  listDocuments,
  searchDocuments,
  type SearchHit,
} from '../../reader/sqliteFtsDatabase';
import HeadlessBrowserController, { type ExtractedPage } from '../../rpa/HeadlessBrowserController';

interface DocumentItem {
  docId: string;
  title: string;
  pageCount: number;
  createdAt: number;
}

export default function KnowledgeVaultScreen() {
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [showBrowser, setShowBrowser] = useState(false);

  async function refresh(): Promise<void> {
    setDocuments(await listDocuments());
  }

  useEffect(() => {
    void refresh();
  }, []);

  async function importDocument(): Promise<void> {
    setBusy(true);
    setMessage('');
    try {
      const result = await pickAndIndexDocument();
      setMessage('Indexed ' + result.title + ' (' + result.pages + ' page(s), ' + result.chunks + ' chunks).');
      await refresh();
    } catch (error) {
      const cancelled = error instanceof Error && error.message.toLowerCase().includes('cancel');
      if (!cancelled) setMessage(error instanceof Error ? error.message : 'Import failed.');
    } finally {
      setBusy(false);
    }
  }

  async function search(): Promise<void> {
    setBusy(true);
    try {
      setHits(await searchDocuments(query, 10));
      setMessage('');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Search failed.');
    } finally {
      setBusy(false);
    }
  }

  async function handlePage(page: ExtractedPage): Promise<void> {
    if (page.error) {
      setMessage(page.error);
      return;
    }
    if (!page.text || !page.url) {
      setMessage('Page load hui, lekin readable text nahi mila.');
      return;
    }
    try {
      const indexed = await indexWebPage(page.title || page.url, page.url, page.text);
      setMessage('Indexed web page: ' + indexed.title + ' (' + indexed.chunks + ' chunks).');
      await refresh();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Web page indexing failed.');
    }
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.heading}>Knowledge Vault</Text>
      <Text style={styles.caption}>Digital PDFs are extracted in small page batches. Scanned image-only PDFs need OCR and may return no text.</Text>

      <Pressable style={styles.primaryButton} disabled={busy} onPress={() => void importDocument()}>
        <Text style={styles.buttonText}>{busy ? 'Working…' : 'Import PDF / TXT / MD / CSV'}</Text>
      </Pressable>
      {message ? <Text style={styles.message}>{message}</Text> : null}

      <Text style={styles.sectionTitle}>Indexed documents ({documents.length})</Text>
      {documents.length === 0 ? (
        <Text style={styles.empty}>No documents imported yet.</Text>
      ) : documents.map((document) => (
        <View key={document.docId} style={styles.documentCard}>
          <Text style={styles.documentTitle}>{document.title}</Text>
          <Text style={styles.documentMeta}>{document.pageCount} page(s)</Text>
        </View>
      ))}

      <Text style={styles.sectionTitle}>Search your documents</Text>
      <View style={styles.searchRow}>
        <TextInput
          value={query}
          onChangeText={setQuery}
          placeholder="Ask a question from your files"
          placeholderTextColor="#64748B"
          style={styles.searchInput}
        />
        <Pressable style={styles.searchButton} onPress={() => void search()} disabled={busy}>
          <Text style={styles.buttonText}>Search</Text>
        </Pressable>
      </View>
      {busy ? <ActivityIndicator color="#60A5FA" /> : null}
      {hits.map((hit, index) => (
        <View key={hit.docId + '_' + hit.chunkIndex + '_' + index} style={styles.hitCard}>
          <Text style={styles.hitTitle}>{hit.title} · page {hit.pageNumber}</Text>
          <Text style={styles.hitText}>{hit.content}</Text>
        </View>
      ))}

      <Pressable style={styles.secondaryButton} onPress={() => setShowBrowser((value) => !value)}>
        <Text style={styles.buttonText}>{showBrowser ? 'Hide web reader' : 'Open user-visible web reader'}</Text>
      </Pressable>
      <Text style={styles.caption}>This reader does not run invisibly or log into websites. It indexes readable public text from pages you open here.</Text>
      <HeadlessBrowserController visible={showBrowser} onExtract={(page) => void handlePage(page)} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B1220' },
  content: { padding: 14, gap: 10 },
  heading: { color: '#FFFFFF', fontSize: 20, fontWeight: '800' },
  caption: { color: '#94A3B8', fontSize: 12, lineHeight: 18 },
  primaryButton: { backgroundColor: '#2563EB', borderRadius: 9, padding: 13, alignItems: 'center' },
  secondaryButton: { backgroundColor: '#334155', borderRadius: 9, padding: 13, alignItems: 'center' },
  buttonText: { color: '#FFFFFF', fontWeight: '700', fontSize: 12 },
  message: { color: '#FDE68A', fontSize: 12 },
  sectionTitle: { color: '#E2E8F0', fontSize: 15, fontWeight: '700', marginTop: 10 },
  empty: { color: '#64748B', fontSize: 12 },
  documentCard: { backgroundColor: '#172338', borderRadius: 8, padding: 10 },
  documentTitle: { color: '#FFFFFF', fontSize: 13, fontWeight: '600' },
  documentMeta: { color: '#94A3B8', fontSize: 10, marginTop: 3 },
  searchRow: { flexDirection: 'row', gap: 8 },
  searchInput: { flex: 1, color: '#FFFFFF', backgroundColor: '#172338', borderRadius: 8, paddingHorizontal: 10, minHeight: 44 },
  searchButton: { backgroundColor: '#1D4ED8', paddingHorizontal: 14, borderRadius: 8, justifyContent: 'center' },
  hitCard: { backgroundColor: '#172338', borderRadius: 8, padding: 10, gap: 5 },
  hitTitle: { color: '#93C5FD', fontSize: 11, fontWeight: '700' },
  hitText: { color: '#E2E8F0', fontSize: 12, lineHeight: 18 },
});
