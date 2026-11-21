---
label: The terminal node
icon: terminal
order: 530
---

# The terminal node

The terminal node is the same conversation in a shell. It signs in, picks her, downloads her body into the cache, connects and
chats in text. What her body would play is printed instead of drawn.

```bash
mvn -q -pl joi-node compile org.codehaus.mojo:exec-maven-plugin:java \
  -Dexec.mainClass=com.github.enerccio.joi.node.JoiNodeCli -Dexec.args="http://localhost:8080"
```

The address is the server's; without it the last one used is taken (else `http://localhost:8080`). Sign-in: a token in the environment
variable `JOI_TOKEN`, else the device grant (it prints a code and a link), or nothing on a server that runs without
authentication. The settings are in the same home as the JavaFX node's (`~/.joi-client`, or the folder named by `--joi-home-dir` or `JOI_HOME_DIR`), so give the flag
to both to use the same login: `JoiNodeCli --joi-home-dir ~/.joi-dev http://localhost:8080`.

## Talking

Type a line and press Return: it is your message. Her reply streams in; her animations are printed as they would play.

## Commands

| Command | What it does |
|---|---|
| `/stop` | stops her reply |
| `/sleep` | she falls asleep |
| `/quit` | ends the node |
| `/yes`, `/always`, `/no [reason]` | answer the oldest task of hers that waits for you |
| `/tasks` | lists her tasks |
| `/auto` | lists the tools approved ahead of time |
| `/auto on <tool>`, `/auto off <tool>` | approves a tool ahead of time, or takes it back |
| `/notify` | says whether she gets the notification tool |
| `/notify off`, `/notify ask`, `/notify allow` | sets it |
| `/notify test` | shows a test notification |

When a task of hers waits for your answer the node prints the question: the title, the tool, what it does, what she asked for and
the page. See [Answering her questions](approvals.md).
