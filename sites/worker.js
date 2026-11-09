export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const asset = await env.ASSETS.fetch(request);

    if (asset.status !== 404) return asset;

    /* Single-page site: unknown clean paths fall back to the landing page. */
    if (!path.includes('.')) {
      return env.ASSETS.fetch(new Request(new URL('/index.html', url), request));
    }

    return new Response('Not found', {
      status: 404,
      headers: { 'content-type': 'text/plain; charset=utf-8' }
    });
  }
};
