const fakes = {
  'npm:@anthropic-ai/sdk@1.2.0': new URL('./fake-anthropic.ts', import.meta.url).href,
  'npm:@supabase/supabase-js@2': new URL('./fake-supabase.ts', import.meta.url).href,
};
export async function resolve(specifier, context, next) {
  if (fakes[specifier]) return { url: fakes[specifier], shortCircuit: true };
  return next(specifier, context);
}
