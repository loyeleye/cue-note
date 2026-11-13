// A stand-in for the Claude SDK: answers each request with the cards the
// test queued, for the block labels that appear in the prompt.
export interface Call { model: string; system: string; content: string; betas?: string[]; fallbacks?: unknown; effort?: string }
export const calls: Call[] = [];
export const answers: { cards: Record<string, unknown>[]; exercises?: Record<string, unknown>[] } = { cards: [] };
export const behavior: { stop: string } = { stop: 'end_turn' };

class APIError extends Error { status?: number }

function stream(params: any) {
  return {
    async finalMessage() {
      const content: string = params.messages[0].content;
      calls.push({ model: params.model, system: params.system, content, betas: params.betas, fallbacks: params.fallbacks,
                   effort: params.output_config?.effort });
      const labels = new Set([...content.matchAll(/^(B\d+) at /gm)].map((m) => m[1]));
      const cards = answers.cards.filter((c) => labels.has(c.block as string));
      const heads = new Set([...content.matchAll(/^== (E\d+): /gm)].map((m) => m[1]));
      const exercises = (answers.exercises ?? []).filter((e) => heads.has(e.exercise as string));
      return {
        stop_reason: behavior.stop,
        usage: { input_tokens: 1000, output_tokens: 200, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
        content: [{ type: 'thinking', thinking: '' }, { type: 'text', text: JSON.stringify({ cards, exercises }) }],
      };
    },
  };
}

export default class Anthropic {
  static APIError = APIError;
  constructor(_: unknown) {}
  messages = { stream };
  beta = { messages: { stream } };
}
