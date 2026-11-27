// Injected by the background page into a tab of a site the user granted: the media of the page, read and controlled.
// A generic adapter on the page's <video> and <audio> elements and the Media Session's metadata, and one for YouTube.
// Defines window.__joiMedia(op, args) in the content script's own world, once.
(() => {
  "use strict";
  if (window.__joiMedia) {
    return;
  }

  const youtube = /(^|\.)youtube\.com$/.test(location.hostname);

  function visibleArea(el) {
    const r = el.getBoundingClientRect();
    return Math.max(0, r.width) * Math.max(0, r.height);
  }

  // the element that matters: the one playing, else the largest visible video, else the first one
  function mainElement() {
    const all = Array.from(document.querySelectorAll("video, audio"));
    if (!all.length) {
      return null;
    }
    const playing = all.filter((e) => !e.paused && !e.ended);
    if (playing.length) {
      return playing.sort((a, b) => visibleArea(b) - visibleArea(a))[0];
    }
    const withSource = all.filter((e) => e.currentSrc || e.src);
    const pool = withSource.length ? withSource : all;
    return pool.sort((a, b) => visibleArea(b) - visibleArea(a))[0];
  }

  function title() {
    const meta = navigator.mediaSession && navigator.mediaSession.metadata;
    if (meta && meta.title) {
      return meta.artist ? meta.title + " — " + meta.artist : meta.title;
    }
    return youtube ? document.title.replace(/ - YouTube$/, "") : document.title;
  }

  function captionsButton() {
    return youtube ? document.querySelector(".ytp-subtitles-button") : null;
  }

  function captionsOn(el) {
    const button = captionsButton();
    if (button) {
      return button.getAttribute("aria-pressed") === "true";
    }
    if (el && el.textTracks && el.textTracks.length) {
      return Array.from(el.textTracks).some((t) => t.mode === "showing");
    }
    return null;
  }

  function status() {
    const el = mainElement();
    if (!el) {
      return null;
    }
    const out = {
      kind: el.tagName.toLowerCase(),
      playing: !el.paused && !el.ended,
      title: title(),
      position: el.currentTime,
      volume: Math.round(el.volume * 100),
      muted: el.muted,
      speed: el.playbackRate,
    };
    if (isFinite(el.duration)) {
      out.duration = el.duration;
    }
    const captions = captionsOn(el);
    if (captions !== null) {
      out.captions = captions;
    }
    return out;
  }

  function click(selector) {
    const button = document.querySelector(selector);
    if (!button) {
      return false;
    }
    button.click();
    return true;
  }

  function number(value, name) {
    const n = Number(value);
    if (!isFinite(n)) {
      throw new Error(name + " needs a number.");
    }
    return n;
  }

  async function control(action, value) {
    const el = mainElement();
    if (!el && !["next", "previous"].includes(action)) {
      throw new Error("There is no video or sound on this page.");
    }
    switch (action) {
      case "play":
        await el.play();
        break;
      case "pause":
        el.pause();
        break;
      case "toggle":
        if (el.paused) {
          await el.play();
        } else {
          el.pause();
        }
        break;
      case "seek":
        el.currentTime = Math.max(0, number(value, "seek"));
        break;
      case "seek_by":
        el.currentTime = Math.max(0, el.currentTime + number(value, "seek_by"));
        break;
      case "volume":
        el.volume = Math.min(100, Math.max(0, number(value, "volume"))) / 100;
        if (el.volume > 0) {
          el.muted = false;
        }
        break;
      case "speed":
        el.playbackRate = Math.min(4, Math.max(0.25, number(value, "speed")));
        break;
      case "captions": {
        const want = value === true || value === "true" || value === "on";
        const button = captionsButton();
        if (button) {
          if ((button.getAttribute("aria-pressed") === "true") !== want) {
            button.click();
            // a video without captions keeps the button as it was
            await new Promise((resolve) => setTimeout(resolve, 400));
            if ((button.getAttribute("aria-pressed") === "true") !== want) {
              throw new Error(want ? "This video has no captions." : "The captions could not be turned off.");
            }
          }
        } else if (el.textTracks && el.textTracks.length) {
          const tracks = Array.from(el.textTracks);
          tracks.forEach((t, i) => { t.mode = want && i === 0 ? "showing" : "disabled"; });
        } else {
          throw new Error("This page has no captions.");
        }
        break;
      }
      case "next":
        if (!(youtube && click(".ytp-next-button"))) {
          throw new Error("This page has no next.");
        }
        break;
      case "previous":
        if (youtube && click(".ytp-prev-button")) {
          break;
        }
        if (el) {
          el.currentTime = 0;
          break;
        }
        throw new Error("This page has no previous.");
      default:
        throw new Error("Unknown action " + action + ".");
    }
    return status();
  }

  window.__joiMedia = (op, args) => (op === "status" ? status() : control(args.action, args.value));
})();
