(function () {
  'use strict';

  var MAX_PHOTO = 1600;   // the biggest side of the photo we upload
  var MAX_THUMB = 400;    // the biggest side of the thumbnail we upload
  var HEADERS = ['x-cache', 'age', 'cache-control', 'content-encoding', 'x-amz-cf-pop'];

  // The new size of an image so that its biggest side is at most `max` (never bigger than the original).
  function fitSize(width, height, max) {
    var scale = Math.min(1, max / Math.max(width, height));
    return { w: Math.max(1, Math.round(width * scale)), h: Math.max(1, Math.round(height * scale)) };
  }

  if (typeof module !== 'undefined') { module.exports = { fitSize: fitSize }; }
  if (typeof document === 'undefined') { return; }

  var $ = function (selector) { return document.querySelector(selector); };
  var photos = [];

  function say(element, text, isError) {
    element.textContent = text;
    element.className = isError ? 'error' : 'muted';
  }

  // ------------------------------------------------------------------ the list: the API first, the static file if there is no API yet
  function showHeaders(response, url) {
    var body = $('#headers tbody');
    body.textContent = '';
    var rows = [['request', url], ['status', response.status]];
    HEADERS.forEach(function (name) { rows.push([name, response.headers.get(name) || '(not sent)']); });
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
  }

  function loadList() {
    say($('#source'), 'Loading the photos ...');
    return fetch('/api/photos', { cache: 'no-store' }).then(function (response) {
      if (!response.ok) { throw new Error('no API: ' + response.status); }
      return response.json().then(function (data) {
        showHeaders(response, '/api/photos');
        say($('#source'), 'The list comes from the API (/api/photos).');
        return data;
      });
    }).catch(function () {
      return fetch('/data/photos.json', { cache: 'no-store' }).then(function (response) {
        showHeaders(response, '/data/photos.json');
        say($('#source'), 'The list comes from a static file (/data/photos.json): there is no API yet.');
        return response.json();
      });
    }).then(function (data) {
      photos = data.photos || [];
      render();
    }).catch(function (problem) {
      say($('#source'), 'The photos could not be loaded: ' + problem.message, true);
    });
  }

  function render() {
    var wall = $('#wall');
    wall.textContent = '';
    $('#empty').hidden = photos.length > 0;
    photos.forEach(function (photo) {
      var card = document.createElement('div');
      card.className = 'photo';

      var open = document.createElement('button');
      open.type = 'button';
      open.className = 'open';
      var img = document.createElement('img');
      img.src = photo.thumb;
      img.alt = photo.title;
      img.loading = 'lazy';
      open.appendChild(img);
      open.addEventListener('click', function () { openViewer(photo); });

      var row = document.createElement('div');
      row.className = 'row';
      var name = document.createElement('span');
      name.className = 'name';
      name.textContent = photo.title;
      var like = document.createElement('button');
      like.type = 'button';
      like.className = 'like';
      like.textContent = '♥ ' + (photo.likes || 0);
      like.addEventListener('click', function () { sendLike(photo, like); });
      row.appendChild(name);
      row.appendChild(like);

      card.appendChild(open);
      card.appendChild(row);
      wall.appendChild(card);
    });
  }

  function sendLike(photo, button) {
    button.disabled = true;
    fetch('/api/photos/' + encodeURIComponent(photo.id) + '/like', { method: 'POST' }).then(function (response) {
      return response.json().then(function (data) {
        if (!response.ok) { throw new Error(data.error || ('status ' + response.status)); }
        photo.likes = data.likes;          // show the new number at once (the cached list may still say the old one)
        button.textContent = '♥ ' + photo.likes;
      });
    }).catch(function (problem) {
      say($('#source'), 'The like did not work: ' + problem.message, true);
    }).then(function () { button.disabled = false; });
  }

  function openViewer(photo) {
    $('#viewer-img').src = photo.photo;
    $('#viewer-img').alt = photo.title;
    $('#viewer-title').textContent = photo.title;
    $('#viewer').hidden = false;
  }
  $('#viewer-close').addEventListener('click', function () { $('#viewer').hidden = true; });
  $('#viewer').addEventListener('click', function (event) { if (event.target === $('#viewer')) { $('#viewer').hidden = true; } });

  // ------------------------------------------------------------------ upload: the browser makes the thumbnail and sends both files straight to S3
  function toJpeg(bitmap, max) {
    var size = fitSize(bitmap.width, bitmap.height, max);
    var canvas = document.createElement('canvas');
    canvas.width = size.w;
    canvas.height = size.h;
    canvas.getContext('2d').drawImage(bitmap, 0, 0, size.w, size.h);
    return new Promise(function (resolve, reject) {
      canvas.toBlob(function (blob) { blob ? resolve(blob) : reject(new Error('The picture could not be converted.')); }, 'image/jpeg', 0.85);
    });
  }

  function api(path, key, body) {
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Upload-Key': key },
      body: JSON.stringify(body || {})
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (data) {
        if (!response.ok) { throw new Error(data.error || ('status ' + response.status)); }
        return data;
      });
    });
  }

  function put(target, blob) {
    return fetch(target.url, { method: 'PUT', headers: target.headers, body: blob }).then(function (response) {
      if (!response.ok) { throw new Error('The upload to S3 was refused (' + response.status + ').'); }
    });
  }

  $('#upload-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var status = $('#upload-status');
    var file = $('#file').files[0];
    var title = $('#title').value.trim();
    var key = $('#key').value;
    if (!file) { return say(status, 'Choose a photo first.', true); }
    if (!title) { return say(status, 'Give the photo a title.', true); }
    if (!key) { return say(status, 'Enter the upload key.', true); }

    var button = $('#upload-button');
    button.disabled = true;
    say(status, 'Preparing the pictures ...');
    var full, thumb, ticket;
    createImageBitmap(file).then(function (bitmap) {
      return Promise.all([toJpeg(bitmap, MAX_PHOTO), toJpeg(bitmap, MAX_THUMB)]);
    }).then(function (blobs) {
      full = blobs[0];
      thumb = blobs[1];
      say(status, 'Asking for upload addresses ...');
      return api('/api/upload-urls', key);
    }).then(function (data) {
      ticket = data;
      say(status, 'Uploading to S3 ...');
      return Promise.all([put(ticket.photo, full), put(ticket.thumb, thumb)]);
    }).then(function () {
      say(status, 'Saving ...');
      return api('/api/photos', key, { id: ticket.id, title: title });
    }).then(function (saved) {
      photos.unshift(saved);                 // show it at once; other visitors see it when the cached list expires
      render();
      $('#upload-form').reset();
      say(status, 'Done. Other visitors see it when the cached list expires (a short time).');
    }).catch(function (problem) {
      say(status, 'The upload failed: ' + problem.message, true);
    }).then(function () { button.disabled = false; });
  });

  $('#reload').addEventListener('click', loadList);
  loadList();
})();
