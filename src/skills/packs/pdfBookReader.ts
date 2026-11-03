import { searchDocuments, type SearchHit } from '../../reader/sqliteFtsDatabase';

export async function queryKnowledgeVault(question: string): Promise<SearchHit[]> {
  return searchDocuments(question, 3);
}
