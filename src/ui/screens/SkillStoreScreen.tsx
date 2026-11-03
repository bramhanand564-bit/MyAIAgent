import React, { useEffect, useState } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import {
  getBuiltinSkills,
  getImportedSkillMetadata,
  importSkillPackMetadata,
  loadSkillPreferences,
  setSkillEnabled,
  type ImportedSkillPackMetadata,
  type SkillDefinition,
} from '../../skills/skillRegistry';

export default function SkillStoreScreen() {
  const [skills, setSkills] = useState<SkillDefinition[]>(getBuiltinSkills());
  const [imported, setImported] = useState<ImportedSkillPackMetadata[]>(getImportedSkillMetadata());
  const [error, setError] = useState('');

  async function refresh(): Promise<void> {
    await loadSkillPreferences();
    setSkills(getBuiltinSkills());
    setImported(getImportedSkillMetadata());
  }

  useEffect(() => {
    void refresh();
  }, []);

  async function toggle(skillId: string, value: boolean): Promise<void> {
    try {
      await setSkillEnabled(skillId, value);
      setSkills(getBuiltinSkills());
      setError('');
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : 'Skill update failed.');
    }
  }

  async function importPack(): Promise<void> {
    try {
      const skill = await importSkillPackMetadata();
      setImported(getImportedSkillMetadata());
      setError('Imported metadata: ' + skill.name + '. It is not executable.');
    } catch (problem) {
      const cancelled = problem instanceof Error && problem.message.toLowerCase().includes('cancel');
      if (!cancelled) setError(problem instanceof Error ? problem.message : 'Import failed.');
    }
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.heading}>Skill Store</Text>
      <Text style={styles.caption}>Built-in skills use fixed, allow-listed handlers. Imported JSON files are stored as metadata only; their scripts are never executed.</Text>
      <Pressable style={styles.importButton} onPress={() => void importPack()}>
        <Text style={styles.buttonText}>Import skill JSON metadata</Text>
      </Pressable>
      {error ? <Text style={styles.error}>{error}</Text> : null}

      <Text style={styles.sectionTitle}>Built-in skills</Text>
      {skills.map((skill) => (
        <View key={skill.id} style={styles.card}>
          <View style={styles.cardHeader}>
            <View style={styles.titleBox}>
              <Text style={styles.name}>{skill.name}</Text>
              <Text style={styles.id}>{skill.id}</Text>
            </View>
            <Switch
              value={skill.enabled}
              onValueChange={(value) => void toggle(skill.id, value)}
              trackColor={{ false: '#475569', true: '#2563EB' }}
              thumbColor={skill.enabled ? '#FFFFFF' : '#CBD5E1'}
            />
          </View>
          <Text style={styles.description}>{skill.description}</Text>
          <Text style={styles.keywords}>Triggers: {skill.keywords.join(', ')}</Text>
        </View>
      ))}

      <Text style={styles.sectionTitle}>Imported metadata ({imported.length})</Text>
      {imported.map((skill) => (
        <View key={skill.id} style={styles.card}>
          <Text style={styles.name}>{skill.name}</Text>
          <Text style={styles.id}>{skill.id} · metadata only · not executable</Text>
          <Text style={styles.description}>{skill.description}</Text>
          <Text style={styles.keywords}>Keywords: {skill.keywords.join(', ')}</Text>
        </View>
      ))}
      {imported.length === 0 ? <Text style={styles.empty}>No imported JSON skill metadata yet.</Text> : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B1220' },
  content: { padding: 14, gap: 12 },
  heading: { color: '#FFFFFF', fontSize: 20, fontWeight: '800' },
  caption: { color: '#94A3B8', fontSize: 12, lineHeight: 18 },
  importButton: { backgroundColor: '#2563EB', padding: 12, borderRadius: 9, alignItems: 'center' },
  buttonText: { color: '#FFFFFF', fontSize: 12, fontWeight: '700' },
  sectionTitle: { color: '#E2E8F0', fontSize: 15, fontWeight: '700', marginTop: 5 },
  card: { padding: 14, borderRadius: 12, backgroundColor: '#172338', borderWidth: 1, borderColor: '#26364F', gap: 8 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  titleBox: { flex: 1, gap: 3 },
  name: { color: '#FFFFFF', fontSize: 15, fontWeight: '700' },
  id: { color: '#64748B', fontSize: 10 },
  description: { color: '#CBD5E1', fontSize: 12, lineHeight: 18 },
  keywords: { color: '#93C5FD', fontSize: 10 },
  error: { color: '#FDE68A', fontSize: 12 },
  empty: { color: '#64748B', fontSize: 12 },
});
