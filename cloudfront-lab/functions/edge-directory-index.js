// CloudFront Function, event type: viewer-request, runtime: cloudfront-js-2.0 (Lab 10).
// The S3 REST endpoint has no "index document" for sub-folders: /docs/ is looked up as the object "docs/" and not found.
// This turns a path that ends with a slash into the index page of that folder.
// (The root "/" is handled by the distribution's "default root object", so it is left alone.)
function handler(event) {
  var request = event.request;
  var uri = request.uri;

  if (uri.length > 1 && uri.endsWith('/')) {
    request.uri = uri + 'index.html';
  }

  return request;
}
