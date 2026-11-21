(function () {
  'use strict';

  var $ = function (selector) { return document.querySelector(selector); };
  var page = document.body.dataset.page;

  function api(method, url, body, headers) {
    var options = { method: method, headers: Object.assign({ 'Accept': 'application/json' }, headers || {}) };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    return fetch(url, options).then(function (response) {
      var read = response.status === 204 ? Promise.resolve(null) : response.json().catch(function () { return null; });
      return read.then(function (data) {
        if (!response.ok) {
          var error = new Error((data && data.error) || 'Something went wrong. Please try again.');
          error.status = response.status;
          throw error;
        }
        return data;
      });
    });
  }

  function formatSize(bytes) {
    if (bytes < 1024) { return bytes + ' B'; }
    var units = ['KB', 'MB', 'GB', 'TB'];
    var value = bytes, unit = -1;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
    return value.toFixed(1) + ' ' + units[unit];
  }

  function formatTime(iso) {
    return new Date(iso).toLocaleString();
  }

  function timeLeft(iso) {
    var seconds = Math.floor((new Date(iso).getTime() - Date.now()) / 1000);
    if (seconds <= 0) { return 'has expired'; }
    var days = Math.floor(seconds / 86400), hours = Math.floor((seconds % 86400) / 3600), minutes = Math.floor((seconds % 3600) / 60);
    if (days > 0) { return 'expires in ' + days + ' d ' + hours + ' h'; }
    if (hours > 0) { return 'expires in ' + hours + ' h ' + minutes + ' min'; }
    return 'expires in ' + Math.max(1, minutes) + ' min';
  }

  function show(element, text) {
    element.textContent = text;
    element.hidden = false;
  }

  function copyOnClick(button, input) {
    button.addEventListener('click', function () {
      input.select();
      var done = function () { button.textContent = 'Copied'; setTimeout(function () { button.textContent = 'Copy'; }, 1500); };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(input.value).then(done, function () { document.execCommand('copy'); done(); });
      } else {
        document.execCommand('copy');
        done();
      }
    });
  }

  function upload(target, file, onProgress) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open(target.method, target.url);
      Object.keys(target.headers || {}).forEach(function (name) { xhr.setRequestHeader(name, target.headers[name]); });
      xhr.upload.onprogress = function (event) { if (event.lengthComputable) { onProgress(event.loaded / event.total); } };
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) { resolve(); } else { reject(new Error('The upload was refused (' + xhr.status + ').')); }
      };
      xhr.onerror = function () { reject(new Error('The upload failed. Check your connection (and the bucket CORS setting when using S3).')); };
      xhr.send(file);
    });
  }

  // ------------------------------------------------------------------ start page
  function home() {
    var form = $('#create-form');
    var fileInput = $('#file');
    var drop = $('#drop');
    var dropText = $('#drop-text');
    var button = $('#create-button');
    var progress = $('#progress');
    var error = $('#create-error');
    var maxBytes = Number(document.body.dataset.maxMb) * 1024 * 1024;

    function chosen() {
      var file = fileInput.files[0];
      dropText.textContent = file ? file.name + ' (' + formatSize(file.size) + ')' : 'Choose a file or drop it here';
    }
    fileInput.addEventListener('change', chosen);
    ['dragenter', 'dragover'].forEach(function (name) {
      drop.addEventListener(name, function (event) { event.preventDefault(); drop.classList.add('over'); });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      drop.addEventListener(name, function (event) { event.preventDefault(); drop.classList.remove('over'); });
    });
    drop.addEventListener('drop', function (event) {
      if (event.dataTransfer && event.dataTransfer.files.length > 0) {
        fileInput.files = event.dataTransfer.files;
        chosen();
      }
    });

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      error.hidden = true;
      var file = fileInput.files[0];
      if (!file) { return show(error, 'Please choose a file first.'); }
      if (file.size < 1) { return show(error, 'This file is empty.'); }
      if (file.size > maxBytes) { return show(error, 'This file is bigger than ' + document.body.dataset.maxMb + ' MB.'); }
      var password = $('#password').value;
      if (password && (password.length < 4 || password.length > 64)) { return show(error, 'The password must have 4 to 64 characters.'); }
      var limit = $('#maxDownloads').value;

      button.disabled = true;
      button.textContent = 'Uploading...';
      progress.value = 0;
      progress.hidden = false;

      var created;
      api('POST', '/api/links', {
        fileName: file.name,
        contentType: file.type || 'application/octet-stream',
        size: file.size,
        expiresInHours: Number($('#expires').value),
        maxDownloads: limit ? Number(limit) : null,
        password: password || null
      }).then(function (data) {
        created = data;
        return upload(data.upload, file, function (fraction) { progress.value = fraction; });
      }).then(function () {
        return api('POST', '/api/links/' + created.id + '/complete', undefined, { 'X-Manage-Token': created.manageToken });
      }).then(function () {
        $('#create-card').hidden = true;
        $('#share-url').value = created.shareUrl;
        $('#manage-url').value = location.origin + '/m/' + created.id + '#' + created.manageToken;
        $('#qr').src = '/d/' + created.id + '/qr';
        $('#open-link').href = '/d/' + created.id;
        $('#result').hidden = false;
      }).catch(function (problem) {
        show(error, problem.message);
        button.disabled = false;
        button.textContent = 'Create link';
        progress.hidden = true;
      });
    });

    copyOnClick($('#copy-share'), $('#share-url'));
    copyOnClick($('#copy-manage'), $('#manage-url'));
  }

  // ------------------------------------------------------------------ the page of a link
  function link() {
    var id = document.body.dataset.id;
    var base = '/d/' + id;
    var error = $('#link-error');
    var after = $('#after-unlock');
    var previewBox = $('#preview');
    var downloadButton = $('#download-button');

    function tick() { $('#expires-text').textContent = timeLeft(document.body.dataset.expires); }
    tick();
    setInterval(tick, 30000);

    function loadPreview() {
      if (document.body.dataset.preview === 'NONE') { return; }
      api('GET', base + '/preview').then(function (data) {
        var element;
        if (data.kind === 'IMAGE') {
          element = document.createElement('img');
          element.alt = 'Preview';
          element.src = data.url;
        } else if (data.kind === 'VIDEO' || data.kind === 'AUDIO') {
          element = document.createElement(data.kind === 'VIDEO' ? 'video' : 'audio');
          element.controls = true;
          element.preload = 'metadata';
          element.src = data.url;
        } else if (data.kind === 'PDF') {
          element = document.createElement('iframe');
          element.title = 'Preview';
          element.src = data.url;
        } else if (data.kind === 'TEXT') {
          element = document.createElement('div');
          var pre = document.createElement('pre');
          pre.textContent = data.text;
          element.appendChild(pre);
          if (data.truncated) {
            var note = document.createElement('p');
            note.className = 'muted';
            note.textContent = 'Only the beginning of the file is shown.';
            element.appendChild(note);
          }
        }
        if (element) {
          previewBox.appendChild(element);
          previewBox.hidden = false;
        }
      }).catch(function () { /* a missing preview is not an error: the download still works */ });
    }

    var form = $('#unlock-form');
    if (form) {
      form.addEventListener('submit', function (event) {
        event.preventDefault();
        error.hidden = true;
        api('POST', base + '/unlock', { password: $('#unlock-password').value }).then(function () {
          form.hidden = true;
          after.classList.remove('hidden');
          loadPreview();
        }).catch(function (problem) { show(error, problem.message); });
      });
    } else {
      loadPreview();
    }

    downloadButton.addEventListener('click', function () {
      error.hidden = true;
      downloadButton.disabled = true;
      api('POST', base + '/download').then(function (data) {
        window.location.href = data.url;
        setTimeout(function () { downloadButton.disabled = false; }, 3000);
      }).catch(function (problem) {
        show(error, problem.message);
        downloadButton.disabled = problem.status === 410;
        if (problem.status !== 410) { downloadButton.disabled = false; }
      });
    });
  }

  // ------------------------------------------------------------------ manage page
  function manage() {
    var id = document.body.dataset.id;
    var token = decodeURIComponent(location.hash.replace(/^#/, ''));
    var error = $('#manage-error');
    if (!token) {
      return show(error, 'The management key is missing. Open the manage link exactly as it was given to you (with the part after the #).');
    }
    var headers = { 'X-Manage-Token': token };

    function load() {
      api('GET', '/api/links/' + id + '/activity', undefined, headers).then(function (data) {
        $('#m-name').textContent = data.fileName;
        $('#m-size').textContent = formatSize(data.size);
        $('#m-expires').textContent = formatTime(data.expiresAt) + ' (' + timeLeft(data.expiresAt) + ')';
        $('#m-downloads').textContent = data.downloads + (data.maxDownloads ? ' of ' + data.maxDownloads : '');
        $('#m-password').textContent = data.hasPassword ? 'yes' : 'no';
        $('#m-open').href = '/d/' + id;
        var body = $('#m-events tbody');
        body.textContent = '';
        data.events.forEach(function (item) {
          var row = document.createElement('tr');
          [formatTime(item.at), item.ip, item.userAgent].forEach(function (text) {
            var cell = document.createElement('td');
            cell.textContent = text;
            row.appendChild(cell);
          });
          body.appendChild(row);
        });
        $('#m-events').hidden = data.events.length === 0;
        $('#m-none').hidden = data.events.length !== 0;
        $('#manage-body').hidden = false;
      }).catch(function (problem) {
        $('#manage-body').hidden = true;
        show(error, problem.status === 404 ? 'This link does not exist any more (it expired or was deleted).' : problem.message);
      });
    }

    $('#m-delete').addEventListener('click', function () {
      if (!window.confirm('Delete this link and its file now? This cannot be undone.')) { return; }
      api('DELETE', '/api/links/' + id, undefined, headers).then(function () {
        $('#manage-body').hidden = true;
        error.className = 'muted';
        show(error, 'The link and its file were deleted.');
      }).catch(function (problem) { show(error, problem.message); });
    });

    load();
    setInterval(load, 30000);
  }

  if (page === 'home') { home(); }
  if (page === 'link') { link(); }
  if (page === 'manage') { manage(); }
})();
