import SQLite, { type SQLiteDatabase } from 'react-native-sqlite-storage';

SQLite.enablePromise(true);

let databasePromise: Promise<SQLiteDatabase> | null = null;
let ftsAvailable = false;

async function initialiseDatabase(): Promise<SQLiteDatabase> {
  const database = await SQLite.openDatabase({
    name: 'local_ai_agent.db',
    location: 'default',
  });

  await database.executeSql(
    'CREATE TABLE IF NOT EXISTS documents (doc_id TEXT PRIMARY KEY, title TEXT NOT NULL, uri TEXT NOT NULL, page_count INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)',
  );
  await database.executeSql(
    'CREATE TABLE IF NOT EXISTS web_cache (query_hash TEXT PRIMARY KEY, query TEXT NOT NULL, answer_json TEXT NOT NULL, source_url TEXT NOT NULL, timestamp INTEGER NOT NULL)',
  );
  await database.executeSql(
    'CREATE TABLE IF NOT EXISTS cron_jobs (job_id TEXT PRIMARY KEY, title TEXT NOT NULL, cron_expression TEXT NOT NULL, skill_id TEXT NOT NULL, params_json TEXT NOT NULL, is_active INTEGER NOT NULL DEFAULT 1, last_run INTEGER NOT NULL DEFAULT 0)',
  );
  await database.executeSql(
    'CREATE TABLE IF NOT EXISTS skill_packs (skill_id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, keywords_json TEXT NOT NULL, schema_json TEXT NOT NULL, is_enabled INTEGER NOT NULL DEFAULT 1)',
  );
  await database.executeSql(
    'CREATE TABLE IF NOT EXISTS memory_vault (memory_id TEXT PRIMARY KEY, category TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)',
  );

  try {
    await database.executeSql(
      "CREATE VIRTUAL TABLE IF NOT EXISTS doc_chunks USING fts5(doc_id UNINDEXED, doc_title UNINDEXED, page_number UNINDEXED, chunk_index UNINDEXED, content, tokenize='unicode61 remove_diacritics 2')",
    );
    ftsAvailable = true;
  } catch {
    await database.executeSql(
      'CREATE TABLE IF NOT EXISTS doc_chunks (doc_id TEXT NOT NULL, doc_title TEXT NOT NULL, page_number INTEGER NOT NULL, chunk_index INTEGER NOT NULL, content TEXT NOT NULL)',
    );
    ftsAvailable = false;
  }
  return database;
}

export async function getDatabase(): Promise<SQLiteDatabase> {
  if (!databasePromise) databasePromise = initialiseDatabase();
  return databasePromise;
}

export interface DocumentChunk {
  docId: string;
  title: string;
  pageNumber: number;
  chunkIndex: number;
  content: string;
}

export interface SearchHit {
  docId: string;
  title: string;
  pageNumber: number;
  chunkIndex: number;
  content: string;
}

export async function insertDocument(
  docId: string,
  title: string,
  uri: string,
  pageCount: number,
): Promise<void> {
  const database = await getDatabase();
  await database.executeSql(
    'INSERT OR REPLACE INTO documents (doc_id, title, uri, page_count, created_at) VALUES (?, ?, ?, ?, ?)',
    [docId, title, uri, pageCount, Date.now()],
  );
}

export async function insertChunks(chunks: DocumentChunk[]): Promise<void> {
  if (chunks.length === 0) return;
  const database = await getDatabase();
  await database.transaction((transaction) => {
    for (const chunk of chunks) {
      transaction.executeSql(
        'INSERT INTO doc_chunks (doc_id, doc_title, page_number, chunk_index, content) VALUES (?, ?, ?, ?, ?)',
        [chunk.docId, chunk.title, chunk.pageNumber, chunk.chunkIndex, chunk.content],
      );
    }
  });
}

export async function deleteDocument(docId: string): Promise<void> {
  const database = await getDatabase();
  await database.executeSql('DELETE FROM doc_chunks WHERE doc_id = ?', [docId]);
  await database.executeSql('DELETE FROM documents WHERE doc_id = ?', [docId]);
}

export async function listDocuments(): Promise<Array<{
  docId: string; title: string; pageCount: number; createdAt: number;
}>> {
  const database = await getDatabase();
  const [result] = await database.executeSql(
    'SELECT doc_id, title, page_count, created_at FROM documents ORDER BY created_at DESC',
  );
  const rows: Array<{ docId: string; title: string; pageCount: number; createdAt: number }> = [];
  for (let index = 0; index < result.rows.length; index += 1) {
    const row = result.rows.item(index);
    rows.push({
      docId: String(row.doc_id),
      title: String(row.title),
      pageCount: Number(row.page_count),
      createdAt: Number(row.created_at),
    });
  }
  return rows;
}

function buildMatchExpression(query: string): string {
  const words = query.match(/[a-zA-Z0-9\u0900-\u097F]{2,}/g) ?? [];
  const unique = Array.from(new Set(words.map((word) => word.toLowerCase()))).slice(0, 12);
  return unique.map((word) => '"' + word.replace(/"/g, '""') + '"').join(' OR ');
}

export async function searchDocuments(query: string, limit = 3): Promise<SearchHit[]> {
  const cleaned = query.trim();
  if (!cleaned) return [];
  const database = await getDatabase();
  const maximum = Math.max(1, Math.min(limit, 10));
  let result;
  if (ftsAvailable) {
    const match = buildMatchExpression(cleaned);
    if (!match) return [];
    [result] = await database.executeSql(
      'SELECT doc_id, doc_title, page_number, chunk_index, content FROM doc_chunks WHERE doc_chunks MATCH ? ORDER BY bm25(doc_chunks) LIMIT ?',
      [match, maximum],
    );
  } else {
    [result] = await database.executeSql(
      'SELECT doc_id, doc_title, page_number, chunk_index, content FROM doc_chunks WHERE content LIKE ? ORDER BY chunk_index LIMIT ?',
      ['%' + cleaned.replace(/[%_]/g, '') + '%', maximum],
    );
  }

  const hits: SearchHit[] = [];
  for (let index = 0; index < result.rows.length; index += 1) {
    const row = result.rows.item(index);
    hits.push({
      docId: String(row.doc_id),
      title: String(row.doc_title),
      pageNumber: Number(row.page_number),
      chunkIndex: Number(row.chunk_index),
      content: String(row.content),
    });
  }
  return hits;
}

export interface CachedSearch {
  results: Array<{ title: string; snippet: string; url: string }>;
  sourceUrl: string;
}

export async function getCachedSearch(queryHash: string): Promise<CachedSearch | null> {
  const database = await getDatabase();
  const [result] = await database.executeSql(
    'SELECT answer_json, source_url, timestamp FROM web_cache WHERE query_hash = ?',
    [queryHash],
  );
  if (result.rows.length === 0) return null;
  const row = result.rows.item(0);
  if (Date.now() - Number(row.timestamp) > 24 * 60 * 60 * 1000) return null;
  try {
    return {
      results: JSON.parse(String(row.answer_json)) as CachedSearch['results'],
      sourceUrl: String(row.source_url),
    };
  } catch {
    return null;
  }
}

export async function saveCachedSearch(
  queryHash: string,
  query: string,
  value: CachedSearch,
): Promise<void> {
  const database = await getDatabase();
  await database.executeSql(
    'INSERT OR REPLACE INTO web_cache (query_hash, query, answer_json, source_url, timestamp) VALUES (?, ?, ?, ?, ?)',
    [queryHash, query, JSON.stringify(value.results), value.sourceUrl, Date.now()],
  );
}

export interface CronJobRecord {
  jobId: string;
  title: string;
  cronExpression: string;
  skillId: string;
  paramsJson: string;
  isActive: boolean;
  lastRun: number;
}

export async function saveCronJob(job: CronJobRecord): Promise<void> {
  const database = await getDatabase();
  await database.executeSql(
    'INSERT OR REPLACE INTO cron_jobs (job_id, title, cron_expression, skill_id, params_json, is_active, last_run) VALUES (?, ?, ?, ?, ?, ?, ?)',
    [job.jobId, job.title, job.cronExpression, job.skillId, job.paramsJson, job.isActive ? 1 : 0, job.lastRun],
  );
}

export async function getCronJobs(): Promise<CronJobRecord[]> {
  const database = await getDatabase();
  const [result] = await database.executeSql(
    'SELECT job_id, title, cron_expression, skill_id, params_json, is_active, last_run FROM cron_jobs ORDER BY title',
  );
  const rows: CronJobRecord[] = [];
  for (let index = 0; index < result.rows.length; index += 1) {
    const row = result.rows.item(index);
    rows.push({
      jobId: String(row.job_id),
      title: String(row.title),
      cronExpression: String(row.cron_expression),
      skillId: String(row.skill_id),
      paramsJson: String(row.params_json),
      isActive: Number(row.is_active) === 1,
      lastRun: Number(row.last_run),
    });
  }
  return rows;
}

export async function updateCronLastRun(jobId: string, timestamp: number): Promise<void> {
  const database = await getDatabase();
  await database.executeSql('UPDATE cron_jobs SET last_run = ? WHERE job_id = ?', [timestamp, jobId]);
}

export async function saveMemory(category: string, content: string): Promise<void> {
  const database = await getDatabase();
  const id = 'mem_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8);
  await database.executeSql(
    'INSERT INTO memory_vault (memory_id, category, content, created_at) VALUES (?, ?, ?, ?)',
    [id, category, content, Date.now()],
  );
}
