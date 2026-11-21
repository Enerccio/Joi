---
label: Web search and pages
icon: search
order: 580
---

# Web search and pages

Her functions `web_search(question)` and `fetch_page(url, question)` start a task: the stronger model gets the question and two
internal functions, `search` and `read_page`, and loops (at most 10 rounds) until it writes a short answer with the sources it
used. The limits **Searches per task** (6) and **Pages per task** (8) are options. With no deep reasoning model the chat model
does it, and the transcript says so. Everything fetched is treated as data, not as instructions.

The setup is the panel under the permissions in the Tools tabs. In the user's tab, your own settings replace the
administrator's as a whole; **Use the administrator's** takes them back.

## Search sources

The sources are tried in order. Each is enabled or not, and can be limited to specific sites (**Only these sites**, one domain per
line). **Add a source** opens its dialog, with **Test** to search with it as typed.

| Kind | Needs |
|---|---|
| **DuckDuckGo (no key)** | nothing. It has no official API, so a page it cannot read is reported as a failure and it can answer with no results when it rate limits you |
| **SearXNG (your own instance)** | the instance's address; the instance must have `json` in its `search.formats` |
| **Google Programmable Search** | an API key and the search engine id (`cx`) |
| **Brave Search** | an API key |
| **Tavily** | an API key |
| **Perplexity** | an API key |

A key is stored protected and never shown again; **Remove the saved key** deletes it. A source can have a **Proxy of its own**. **On my
own network** marks it as local.

## Options

| Option | Meaning |
|---|---|
| **Proxy**, **Proxy user:password** | one HTTP(S) proxy for all |
| **Timeout** | seconds per request (20) |
| **Results per search** | 8 |
| **Language of the results**, **Safe search**, **User agent** | passed to the sources |
| **Only sources and readers on my own network** | only sources and readers marked local run; the others are refused |
| **Searches per task**, **Pages per task** | the limits above |

## Reading pages

**How a page is read** (also used by a search task to read the pages it found, under the permission of `web_search`):

- **Directly**: the server fetches the page and strips it to text.
- **crawl4ai server**: the server asks a crawl4ai instance to crawl it and uses the markdown.
- **crawl4ai proxy**: your own small wrapper: the server posts `{"url": ...}` and takes `{"content": ...}`.

For the last two give the **Address of the reader** and, if needed, its **Token**. **Characters of a page given to the model**
cuts long pages (20000). **Direct reads may reach addresses of the server's own network** is off by default, so a page she is
led to cannot send the server to localhost or your network. **Test a page** reads an address and shows what came back.

Fetching is anonymous, never with the cookies of your browser.
