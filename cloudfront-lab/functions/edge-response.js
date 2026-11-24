// CloudFront Function, event type: viewer-response, runtime: cloudfront-js-2.0 (Lab 5).
// Adds a header to every answer, also to answers that came from the cache.
function handler(event) {
  var response = event.response;
  response.headers['x-served-by-edge-function'] = { value: 'cf-lab' };
  return response;
}
