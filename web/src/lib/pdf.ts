// Turns a PDF score into page images, in the browser, with pdf.js. Loaded
// only when a PDF is opened, so it doesn't slow down the rest of the app.
export async function pdfPages(source: string | ArrayBuffer, width = 1400): Promise<string[]> {
  const pdfjs = await import('pdfjs-dist');
  const worker = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default;
  pdfjs.GlobalWorkerOptions.workerSrc = worker;
  const task = pdfjs.getDocument(typeof source === 'string' ? { url: source } : { data: source });
  const doc = await task.promise;
  const pages: string[] = [];
  for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const base = page.getViewport({ scale: 1 });
    const viewport = page.getViewport({ scale: width / base.width });
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(viewport.width);
    canvas.height = Math.round(viewport.height);
    await page.render({ canvas, viewport }).promise;
    const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/png'));
    if (blob) pages.push(URL.createObjectURL(blob));
  }
  await task.destroy();
  return pages;
}

export async function pdfPageCount(file: File): Promise<number> {
  const pdfjs = await import('pdfjs-dist');
  const worker = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default;
  pdfjs.GlobalWorkerOptions.workerSrc = worker;
  const task = pdfjs.getDocument({ data: await file.arrayBuffer() });
  const n = (await task.promise).numPages;
  await task.destroy();
  return n;
}
