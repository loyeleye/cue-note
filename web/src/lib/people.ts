// How teachers are named and colored. Other teachers appear by their alias
// ("Tiger"), with the real name only if they've made it public. Students
// always see both: "Tiger (Marigold Stewart)". The server decides which real
// names a viewer gets; this file only formats what came back.
import type { Teacher, TeacherColor } from './types';

export const COLOR_LABEL: Record<TeacherColor, string> = {
  blue: 'Blue', teal: 'Teal', green: 'Green', amber: 'Amber',
  coral: 'Coral', rose: 'Rose', violet: 'Violet', indigo: 'Indigo'
};

/** A CSS color for a teacher; the tokens live in app.css for both themes. */
export const colorVar = (c: TeacherColor | undefined | null) => `var(--t-${c ?? 'blue'})`;
export const softVar = (c: TeacherColor | undefined | null) => `var(--t-${c ?? 'blue'}-soft)`;

/** "Tiger", "Tiger (Marigold Stewart)", or "Tiger (you)". */
export function teacherLabel(t: Pick<Teacher, 'id' | 'alias' | 'display_name'>, meId?: string | null): string {
  if (t.id === meId) return `${t.alias} (you)`;
  const real = t.display_name?.trim();
  return real ? `${t.alias} (${real})` : t.alias;
}

/** A former teacher, or one this viewer can't look up. */
export const UNKNOWN_TEACHER: Teacher = {
  id: '', alias: 'A former teacher', color: 'blue', name_public: false, display_name: null
};

export function findTeacher(teachers: Teacher[], id: string | null | undefined): Teacher {
  return teachers.find((t) => t.id === id) ?? UNKNOWN_TEACHER;
}

export function initials(alias: string): string {
  return alias.trim().slice(0, 2).toUpperCase() || '?';
}
