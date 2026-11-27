// Keeps the download link and footer version in sync with version.json (the
// single source of truth in the repository root). If that cannot be read
// (e.g. the page is served without the file), fall back to the latest GitHub
// Release from the API. The static version text in the HTML is only a last
// fallback shown when both are unreachable.
(function () {
  var repo = '848488489-ff/ZipRepairTool';
  var api = 'https://api.github.com/repos/' + repo + '/releases/latest';

  function apply(tag) {
    if (!tag) { return; }
    var dl = 'https://github.com/' + repo + '/releases/download/' + tag + '/ZipRepairTool.jar';
    var links = document.querySelectorAll('[data-download]');
    for (var i = 0; i < links.length; i++) {
      links[i].href = dl;
      links[i].textContent = links[i].textContent.replace(/\(v[^)]*\)/, '(' + tag + ')');
    }
    var spans = document.querySelectorAll('[data-version]');
    for (var j = 0; j < spans.length; j++) {
      spans[j].textContent = tag;
    }
  }

  // version.json holds "version": "1.5"; release tags are "v" + version.
  fetch('../version.json', { cache: 'no-store' })
    .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
    .then(function (d) { if (d && d.version) { apply('v' + d.version); } })
    .catch(function () {
      return fetch(api, { headers: { 'Accept': 'application/vnd.github+json' } })
        .then(function (r) { return r.json(); })
        .then(function (d) { apply(d && d.tag_name); })
        .catch(function () { /* keep the static fallback version */ });
    });
})();
