// CloudFront Function, event type: viewer-request, runtime: cloudfront-js-2.0 (Lab 5).
// It runs at the edge for EVERY request, also when the answer would come from the cache. Keep it tiny and fast.
function handler(event) {
  var request = event.request;
  var uri = request.uri;

  // 1. Block: there is no public admin area
  if (uri.startsWith('/admin')) {
    return {
      statusCode: 403,
      statusDescription: 'Forbidden',
      headers: { 'content-type': { value: 'text/plain' } },
      body: { encoding: 'text', data: 'Not allowed here.' }
    };
  }

  // 2. Redirect: an old address moves to the new one
  if (uri === '/old-about.html') {
    return {
      statusCode: 301,
      statusDescription: 'Moved Permanently',
      headers: { location: { value: '/about.html' } }
    };
  }

  // 3. Clean URLs: /about is served from /about.html (only when the last part of the path has no dot)
  var last = uri.split('/').pop();
  if (last !== '' && last.indexOf('.') === -1) {
    request.uri = uri + '.html';
  }

  return request;
}
