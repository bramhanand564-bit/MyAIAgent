import { NativeModules } from 'react-native';
import DocumentPicker from 'react-native-document-picker';
import RNFS from 'react-native-fs';
import TextRecognition from '@react-native-ml-kit/text-recognition';
import {
  deleteDocument,
  insertChunks,
  insertDocument,
  type DocumentChunk,
} from './sqliteFtsDatabase';

interface NativePdfExtractor {
  getPageCount(uri: string): Promise<number>;
  extractPages(uri: string, startPage: number, pageCount: number): Promise<Array<{
    page: number;
    text: string;
  }>>;
  renderPages(uri: string, startPage: number, pageCount: number): Promise<Array<{
    page: number;
    uri: string;
  }>>;
}

const pdfExtractor = NativeModules.PdfTextExtractor as NativePdfExtractor | undefined;
const CHUNK_SIZE = 400;
const OVERLAP = 50;
const READ_BYTES = 64 * 1024;
const MAX_TEXT_FILE_BYTES = 25 * 1024 * 1024;
const MAX_WEB_TEXT_CHARS = 80_000;

export interface DocumentSummary {
  docId: string;
  title: string;
  pages: number;
  chunks: number;
}

function makeId(): string {
  return 'doc_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 9);
}

function filePathFromUri(uri: string): string {
  return uri.startsWith('file://') ? uri.slice('file://'.length) : uri;
}

function splitTextIntoChunks(text: string, docId: string, title: string, pageNumber: number, startIndex: number): DocumentChunk[] {
  const normalized = text.replace(/\r/g, '').trim();
  const chunks: DocumentChunk[] = [];
  let offset = 0;
  let index = startIndex;
  while (offset < normalized.length) {
    const part = normalized.slice(offset, offset + CHUNK_SIZE).trim();
    if (part.length > 0) {
      chunks.push({ docId, title, pageNumber, chunkIndex: index, content: part });
      index += 1;
    }
    if (offset + CHUNK_SIZE >= normalized.length) break;
    offset += CHUNK_SIZE - OVERLAP;
  }
  return chunks;
}

async function recognizeImage(uri: string): Promise<string> {
  const result = await TextRecognition.recognize(uri);
  return typeof result.text === 'string' ? result.text : '';
}

async function ingestPdf(uri: string, title: string, docId: string): Promise<{ pages: number; chunks: number }> {
  if (!pdfExtractor) {
    throw new Error('PDF extraction native module is not installed in this build. Install the latest GitHub Actions APK.');
  }
  const pageCount = await pdfExtractor.getPageCount(uri);
  if (pageCount < 1) throw new Error('This PDF has no readable pages.');
  let chunkIndex = 0;

  for (let firstPage = 1; firstPage <= pageCount; firstPage += 10) {
    const pages = await pdfExtractor.extractPages(uri, firstPage, Math.min(10, pageCount - firstPage + 1));
    const scannedPages = pages.filter((page) => !page.text.trim());
    const ocrTextByPage = new Map<number, string>();

    if (scannedPages.length > 0) {
      const rendered = await pdfExtractor.renderPages(
        uri,
        firstPage,
        Math.min(10, pageCount - firstPage + 1),
      );
      for (const image of rendered) {
        if (!scannedPages.some((page) => page.page === image.page)) continue;
        try {
          ocrTextByPage.set(image.page, await recognizeImage(image.uri));
        } finally {
          await RNFS.unlink(filePathFromUri(image.uri)).catch(() => undefined);
        }
      }
    }

    for (const page of pages) {
      const pageText = page.text.trim() ? page.text : (ocrTextByPage.get(page.page) || '');
      if (!pageText.trim()) continue;
      const pageChunks = splitTextIntoChunks(pageText, docId, title, page.page, chunkIndex);
      if (pageChunks.length > 0) {
        await insertChunks(pageChunks);
        chunkIndex += pageChunks.length;
      }
    }
  }
  return { pages: pageCount, chunks: chunkIndex };
}

async function ingestImage(uri: string, title: string, docId: string): Promise<{ pages: number; chunks: number }> {
  const text = await recognizeImage(uri);
  if (!text.trim()) throw new Error('Offline OCR could not detect readable text in this image.');
  const chunks = splitTextIntoChunks(text, docId, title, 1, 0);
  await insertChunks(chunks);
  return { pages: 1, chunks: chunks.length };
}

async function ingestTextFile(uri: string, title: string, docId: string): Promise<{ pages: number; chunks: number }> {
  const path = filePathFromUri(uri);
  const stat = await RNFS.stat(path);
  const fileSize = Number(stat.size);
  if (fileSize > MAX_TEXT_FILE_BYTES) {
    throw new Error('Text import is limited to 25 MB per file to protect phone memory.');
  }
  let position = 0;
  let buffer = '';
  let chunkIndex = 0;
  const pending: DocumentChunk[] = [];

  while (position < fileSize) {
    const length = Math.min(READ_BYTES, fileSize - position);
    const part = await RNFS.read(path, length, position, 'utf8');
    position += length;
    buffer += part;

    while (buffer.length >= CHUNK_SIZE) {
      const content = buffer.slice(0, CHUNK_SIZE).trim();
      if (content) {
        pending.push({ docId, title, pageNumber: 1, chunkIndex, content });
        chunkIndex += 1;
      }
      buffer = buffer.slice(CHUNK_SIZE - OVERLAP);
      if (pending.length >= 50) {
        const batch = pending.splice(0, pending.length);
        await insertChunks(batch);
      }
    }
  }
  if (buffer.trim()) {
    pending.push({ docId, title, pageNumber: 1, chunkIndex, content: buffer.trim() });
    chunkIndex += 1;
  }
  if (pending.length > 0) await insertChunks(pending);
  return { pages: 1, chunks: chunkIndex };
}

export async function pickAndIndexDocument(): Promise<DocumentSummary> {
  const picked = await DocumentPicker.pickSingle({
    type: [
      DocumentPicker.types.allFiles,
      'image/jpeg',
      'image/png',
      'image/webp',
    ],
    copyTo: 'documentDirectory',
  });
  const title = picked.name || 'Imported document';
  const uri = picked.fileCopyUri || picked.uri;
  const extension = title.toLowerCase().split('.').pop() || '';
  const docId = makeId();

  if (!uri) throw new Error('The selected file has no readable URI.');
  const isPdf = extension === 'pdf' || picked.type === 'application/pdf';
  const isImage = ['jpg', 'jpeg', 'png', 'webp'].includes(extension) ||
    (picked.type || '').startsWith('image/');
  const isText = ['txt', 'md', 'csv', 'html', 'htm', 'json', 'xml'].includes(extension);
  if (!isPdf && !isText && !isImage) {
    throw new Error('Supported formats: digital/scanned PDF, TXT, Markdown, CSV, HTML, JSON, XML and JPG/PNG/WebP images.');
  }

  try {
    const summary = isPdf
      ? await ingestPdf(uri, title, docId)
      : isImage
        ? await ingestImage(uri, title, docId)
        : await ingestTextFile(uri, title, docId);
    await insertDocument(docId, title, uri, summary.pages);
    return { docId, title, pages: summary.pages, chunks: summary.chunks };
  } catch (error) {
    await deleteDocument(docId).catch(() => undefined);
    throw error;
  }
}

export async function indexWebPage(
  title: string,
  uri: string,
  text: string,
): Promise<DocumentSummary> {
  const cleaned = text.trim().slice(0, MAX_WEB_TEXT_CHARS);
  if (!cleaned) throw new Error('The web page did not contain readable text.');
  const docId = makeId();
  const safeTitle = title.trim().slice(0, 180) || uri;
  const chunks = splitTextIntoChunks(cleaned, docId, safeTitle, 1, 0);
  try {
    await insertChunks(chunks);
    await insertDocument(docId, safeTitle, uri, 1);
    return { docId, title: safeTitle, pages: 1, chunks: chunks.length };
  } catch (error) {
    await deleteDocument(docId).catch(() => undefined);
    throw error;
  }
}
