// CloudFront Function, event type: viewer-request, runtime: cloudfront-js-2.0 (Lab 10).
// For a single-page app: every path that is not a file is a page of the app, so it is served from /index.html
// (with status 200), and the browser's JavaScript shows the right screen. Files (anything with a dot in the last
// part of the path) are served as they are. Attach it to the default behavior only, not to /api/* or /dyn/*.
function handler(event) {
  var request = event.request;
  var uri = request.uri;
  var last = uri.split('/').pop();

  if (uri !== '/' && last.indexOf('.') === -1) {
    request.uri = '/index.html';
  }

  return request;
}
