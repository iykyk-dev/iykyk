// Fills the download buttons and "What's new" from release.json (written at deploy
// time from the latest GitHub release). Without it the static links still work.
(function () {
  var ua = navigator.userAgent || "";
  var platform =
    /iPhone|iPad|iPod/i.test(ua) ? "ios" :
    /Android/i.test(ua) ? "android" :
    /Windows/i.test(ua) ? "windows" :
    /Mac OS X/i.test(ua) ? "mac" : "other";

  function esc(text) {
    return String(text).replace(/[&<>"]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
  }

  // "- item" lines become a list, other lines paragraphs.
  function notesHtml(text) {
    var html = "", list = false;
    text.split("\n").forEach(function (line) {
      line = line.trim();
      if (/^[-*] /.test(line)) {
        if (!list) { html += "<ul>"; list = true; }
        html += "<li>" + esc(line.slice(2)) + "</li>";
      } else {
        if (list) { html += "</ul>"; list = false; }
        if (line) html += "<p>" + esc(line) + "</p>";
      }
    });
    if (list) html += "</ul>";
    return html;
  }

  function apply(release) {
    var main = document.getElementById("btn-main");
    var other = document.getElementById("btn-other");
    var apk = release.android || {};
    var android = (apk.universal || {}).url;
    var windows = (release.windows || {}).url;

    // Both downloads are always offered; the one for this device comes first.
    function set(button, label, url, primary) {
      button.textContent = label;
      if (url) button.href = url;
      button.className = "btn " + (primary ? "primary" : "secondary");
    }
    if (platform === "android") {
      set(main, "Скачать для Android", android, true);
      set(other, "Для Windows", windows, false);
    } else if (platform === "ios" || platform === "mac") {
      // No iPhone/Mac app yet: Android is what works, iPhone/Mac is shown as coming.
      set(main, "Скачать для Android", android, true);
      set(other, "iPhone и Mac — скоро", null, false);
      other.removeAttribute("href");
      other.className = "btn secondary soon";
      other.setAttribute("aria-disabled", "true");
    } else {
      set(main, "Скачать для Windows", windows, true);
      set(other, "Для Android", android, false);
    }

    var version = document.getElementById("version");
    var date = release.date ? new Date(release.date).toLocaleDateString("ru-RU", { day: "numeric", month: "long", year: "numeric" }) : "";
    version.innerHTML = "Версия " + esc(release.version) + (date ? " · " + esc(date) : "") +
      (release.beta ? '<span class="badge">бета</span>' : "") +
      ' · <a href="#news">Что нового</a>';

    if (release.notes) {
      document.getElementById("news-title").textContent = "Что нового в " + release.version;
      document.getElementById("news-body").innerHTML = notesHtml(release.notes);
      document.getElementById("news").hidden = false;
    }

    var fingerprint = document.getElementById("fingerprint");
    if (fingerprint && release.signature) fingerprint.textContent = release.signature;
    var sums = document.getElementById("checksums");
    if (sums && release.checksums) sums.textContent = release.checksums;
    var page = document.getElementById("release-page");
    if (page && release.page) page.href = release.page;
  }

  fetch("release.json", { cache: "no-cache" })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (release) { if (release) apply(release); })
    .catch(function () {});
})();
