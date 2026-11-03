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
  loadSkillPreferences,
  setSkillEnabled,
  type SkillDefinition,
} from '../../skills/skillRegistry';

export default function SkillStoreScreen() {
  const [skills, setSkills] = useState<SkillDefinition[]>(getBuiltinSkills());
  const [error, setError] = useState('');

  async function refresh(): Promise<void> {
    await loadSkillPreferences();
    setSkills(getBuiltinSkills());
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

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.heading}>Skill Store</Text>
      <Text style={styles.caption}>Only built-in, allow-listed handlers can execute. Imported JSON skills are not executed as code.</Text>
      {error ? <Text style={styles.error}>{error}</Text> : null}
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
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B1220' },
  content: { padding: 14, gap: 12 },
  heading: { color: '#FFFFFF', fontSize: 20, fontWeight: '800' },
  caption: { color: '#94A3B8', fontSize: 12, lineHeight: 18 },
  card: { padding: 14, borderRadius: 12, backgroundColor: '#172338', borderWidth: 1, borderColor: '#26364F', gap: 8 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  titleBox: { flex: 1, gap: 3 },
  name: { color: '#FFFFFF', fontSize: 15, fontWeight: '700' },
  id: { color: '#64748B', fontSize: 10 },
  description: { color: '#CBD5E1', fontSize: 12, lineHeight: 18 },
  keywords: { color: '#93C5FD', fontSize: 10 },
  error: { color: '#FCA5A5', fontSize: 12 },
});
