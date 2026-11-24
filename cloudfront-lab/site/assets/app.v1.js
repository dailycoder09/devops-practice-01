(function () {
  'use strict';

  var $ = function (selector) { return document.querySelector(selector); };

  var opened = $('#opened');
  if (opened) { opened.textContent = new Date().toLocaleTimeString(); }

  // The headers worth looking at when learning CloudFront
  var HEADERS = ['x-cache', 'age', 'cache-control', 'content-encoding', 'content-type', 'content-length', 'etag', 'last-modified', 'x-amz-cf-pop', 'via'];

  var form = $('#inspect-form');
  if (form) {
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      var path = $('#inspect-path').value.trim() || '/';
      var table = $('#inspect-result');
      var error = $('#inspect-error');
      var body = table.querySelector('tbody');
      error.hidden = true;
      table.hidden = true;
      body.textContent = '';
      var started = performance.now();
      // cache: 'no-store' = do not use the browser's own cache, so the answer comes from CloudFront
      fetch(path, { cache: 'no-store' }).then(function (response) {
        var rows = [['status', response.status + ' ' + response.statusText], ['time', Math.round(performance.now() - started) + ' ms']];
        HEADERS.forEach(function (name) {
          var value = response.headers.get(name);
          rows.push([name, value === null ? '(not sent)' : value]);
        });
        rows.forEach(function (row) {
          var tr = document.createElement('tr');
          var th = document.createElement('th');
          var td = document.createElement('td');
          th.textContent = row[0];
          td.textContent = row[1];
          tr.appendChild(th);
          tr.appendChild(td);
          body.appendChild(tr);
        });
        table.hidden = false;
      }).catch(function (problem) {
        error.textContent = 'The request failed: ' + problem.message;
        error.hidden = false;
      });
    });
  }

  var apiForm = $('#api-form');
  if (apiForm) {
    apiForm.addEventListener('submit', function (event) {
      event.preventDefault();
      var result = $('#api-result');
      var ttl = Number($('#api-ttl').value) || 0;
      var url = '/api/time' + (ttl > 0 ? '?ttl=' + ttl : '');
      result.hidden = false;
      result.textContent = 'Calling ' + url + ' ...';
      fetch(url, { cache: 'no-store' }).then(function (response) {
        var info = 'HTTP ' + response.status + '   X-Cache: ' + (response.headers.get('x-cache') || '(not sent)') +
          '   Age: ' + (response.headers.get('age') || '0') + '\n';
        return response.text().then(function (text) { result.textContent = info + text; });
      }).catch(function (problem) {
        result.textContent = 'The request failed: ' + problem.message;
      });
    });
  }
})();
