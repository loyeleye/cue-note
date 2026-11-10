// Lets the tests run index.ts under Node: the edge function's npm: imports
// resolve to the in-memory fakes next to this file.
import { register } from 'node:module';
register(new URL('./hooks.mjs', import.meta.url));
