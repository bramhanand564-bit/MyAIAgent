export const EXTRACT_PUBLIC_PAGE_SCRIPT = String.raw`
(function () {
  try {
    var title = document.title || location.href;
    var description = '';
    var meta = document.querySelector('meta[name="description"]');
    if (meta) description = meta.getAttribute('content') || '';
    var text = document.body ? (document.body.innerText || '') : '';
    var payload = {
      title: title.slice(0, 200),
      description: description.slice(0, 500),
      url: location.href,
      text: text.slice(0, 8000)
    };
    window.ReactNativeWebView.postMessage(JSON.stringify(payload));
  } catch (error) {
    window.ReactNativeWebView.postMessage(JSON.stringify({
      error: String(error)
    }));
  }
  true;
})();
`;
