// The library of suggested diagrams: the drawings are static/diagrams/<id>.svg,
// listed in static/diagrams/index.json (both made by tools/diagrams/gen.py).
import list from '../../static/diagrams/index.json';

export interface Diagram {
  id: string;
  area: string;
  title: string;
  shows: string;
}

export const DIAGRAMS: Diagram[] = list;
const byId = new Map(DIAGRAMS.map((d) => [d.id, d]));

export function diagram(id: string): Diagram | undefined {
  return byId.get(id);
}

export function diagramUrl(id: string): string {
  return `/diagrams/${encodeURIComponent(id)}.svg`;
}
