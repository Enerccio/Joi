// Injected by the background page into a tab of a site the user granted, for read_page and page_screenshot: the page's main text
// (or the selection), and what a picture of it needs (its size, and whether a password field is in view).
// Defines window.__joiPage(op, args) in the content script's own world, once.
(() => {
  "use strict";
  if (window.__joiPage) {
    return;
  }

  const MAX_TEXT = 50000;
  const SKIP = "script, style, noscript, template, svg, canvas, iframe, nav, header, footer, aside, form, button, "
    + "[role=navigation], [role=banner], [role=contentinfo], [role=complementary], [aria-hidden=true], [hidden]";

  // the part of the page that holds what it is about: an article or main, else the element with the most paragraph text
  function mainElement() {
    const marked = document.querySelector("article, main, [role=main]");
    if (marked && marked.innerText && marked.innerText.trim().length > 200) {
      return marked;
    }
    let best = null;
    let bestScore = 0;
    for (const p of document.querySelectorAll("p")) {
      const parent = p.parentElement;
      if (!parent || parent.closest(SKIP)) {
        continue;
      }
      const score = Array.from(parent.children).filter((c) => c.tagName === "P")
        .reduce((sum, c) => sum + (c.innerText || "").length, 0);
      if (score > bestScore) {
        best = parent;
        bestScore = score;
      }
    }
    return bestScore > 400 ? best : document.body;
  }

  // the text without the page's menus and furniture, blank lines folded
  function textOf(element) {
    const copy = element.cloneNode(true);
    copy.querySelectorAll(SKIP).forEach((e) => e.remove());
    // innerText of a detached node has no layout: textContent with line breaks at blocks instead
    copy.querySelectorAll("p, div, li, h1, h2, h3, h4, h5, h6, tr, br, pre, blockquote, section").forEach((e) => {
      e.append("\n");
    });
    copy.querySelectorAll("li").forEach((e) => e.prepend("- "));
    return (copy.textContent || "").replace(/[ \t ]+/g, " ").replace(/ *\n */g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  }

  function read(args) {
    if (args.selection) {
      const selected = String(window.getSelection() || "").trim();
      if (!selected) {
        throw new Error("Nothing is selected on the page.");
      }
      return { text: selected.slice(0, MAX_TEXT), cut: selected.length > MAX_TEXT, selection: true };
    }
    const text = textOf(mainElement());
    return { text: text.slice(0, MAX_TEXT), cut: text.length > MAX_TEXT, selection: false };
  }

  function inView(el) {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  }

  // what a picture needs: the page's size, and whether a password field would be in it
  function measure(args) {
    const root = document.documentElement;
    const width = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0, innerWidth);
    const height = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0, innerHeight);
    const passwords = Array.from(document.querySelectorAll("input[type=password]"));
    const password = passwords.some((p) => args.full_page ? p.getClientRects().length > 0 : inView(p));
    return {
      width, height, viewWidth: innerWidth, viewHeight: innerHeight, scrollX, scrollY, ratio: devicePixelRatio || 1, password,
    };
  }

  window.__joiPage = (op, args) => {
    switch (op) {
      case "read":
        return read(args || {});
      case "measure":
        return measure(args || {});
      default:
        throw new Error("No such page op: " + op);
    }
  };
})();
