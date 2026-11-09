<script lang="ts">
  let { children } = $props();
</script>

<main>
  {@render children()}
</main>

<style>
  :global(:root) {
    --bg: #fbfaf7;
    --fg: #1d1b18;
    --muted: #6b665e;
    --accent: #6d4aa8;
    --border: #e3dfd6;
    color-scheme: light dark;
  }
  @media (prefers-color-scheme: dark) {
    :global(:root) {
      --bg: #161513;
      --fg: #ece8e1;
      --muted: #a39d93;
      --accent: #b79cf0;
      --border: #34312c;
    }
  }
  :global(body) {
    margin: 0;
    background: var(--bg);
    color: var(--fg);
    font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
  }
  main {
    max-width: 640px;
    margin: 0 auto;
    padding: 24px 16px;
  }
</style>
