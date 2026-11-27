---
label: Files and shell
icon: terminal
order: 504
---

# Files and shell

A node can let her work on its machine: read and list files, write them, and run commands. This is the node's **harness**. It is
off until you turn it on, and the node decides every call itself, under a policy that only you change. The server cannot change it.

| Tool | What she can do | When you are asked |
|---|---|---|
| `read_file` | read a text file, by lines (up to 2000 lines a call) | not inside your folders; every time elsewhere |
| `list_files` | see what a folder holds | not inside your folders; every time elsewhere |
| `write_file` | write a whole text file, new or replacing one | every time |
| `run_command` | run a command line in your shell (60 s by default, at most 10 minutes) | every time |

**Your folders** are the folders you choose. Inside them she reads without asking. Outside them, she can still read, but each
read is asked about. With no folders chosen, every read is asked about.

## Turning it on

In the JavaFX node it is **Joi → Files and shell…**: two switches, **Files** and **Shell**, and the list of your folders (**Add a
folder…**, **Remove**). A change applies at once: she gets the tools, or loses them, and is told that her tools changed.

In the terminal node:

| Command | What it does |
|---|---|
| `/files`, `/shell`, `/roots` | say what is on, your folders, and where the policy and the audit are |
| `/files on`, `/files off` | the file tools |
| `/shell ask`, `/shell off` | the shell tool |
| `/roots add <path>`, `/roots remove <path>` | your folders (`~` is your home) |

## When you are asked

The question says what she wants, the real path (or the command and the folder it runs in), and for a write the start of what she
writes. You answer:

- **Allow once**: this call.
- **Allow for session**: this call, and the same again until the node quits. "The same" is the same folder for a read, the same
  file for a write, the same command line in the same folder for the shell.
- **Deny**: she is told you did not allow it.

No answer in two minutes is a no. In the terminal node the answers are `/yes`, `/session` and `/no`.

!!!warning Read the command
A command runs with your rights, as if you typed it. Read it before you allow it, and do not use **Allow for session** for a
command you would not run twice. A command that came from a web page a task read is still just text she passes on.
!!!

## What she never gets

Some paths are refused without asking, whatever path she gives (with `..` or through a link, the real place is checked):

- SSH and GnuPG keys, the cloud and container credentials (`~/.aws`, `~/.azure`, `gcloud`, `~/.kube`, `~/.docker`), `.netrc`,
  `.git-credentials`, the GitHub CLI's config, `.npmrc`, `.pypirc`, Maven's `settings.xml`, the password store, the macOS keychains
  and cookies, and the browsers' profiles.
- The node's own home (`~/.joi-client`): its credentials, the policy and the audit.
- Writes to the system's folders (`/System`, `/Library`, `/usr`, `/bin`, `/etc`...). She can read them, with a question.

A command is refused when it names `sudo`, `su`, `doas`, `pkexec` or `runas`, or one of the paths above. This check is a backstop
for plain mistakes. The real check is you, reading the command.

## The policy file

The policy is `policy.json` in the node's home. The settings above write it, and you can edit it by hand: it is read again on
every call. A file the node cannot read allows nothing.

```json
{
  "files": "on",
  "shell": "ask",
  "roots": ["~/Projects", "~/Documents/notes"],
  "denied": ["~/.ssh", "~/Private"],
  "deniedWrites": ["/System", "/usr"],
  "deniedCommands": ["sudo", "su"]
}
```

`denied`, `deniedWrites` and `deniedCommands` start as the lists above. Leave a key out to keep its default. Write `[]` for
none, but think before you empty `denied`.

## The audit

Every call is written to `audit.jsonl` in the node's home, one line when it is decided and one when it ends: the tool, the real
path or the command, what you answered, the exit code. It never holds a file's content or a command's output. If the node cannot
write the audit, the call does not run.

## What the server sees

The names of your folders go into the descriptions of her tools, so she knows where she can read. What she reads, and what a
command prints, goes to her model like any tool result. A long output is cut to its first and last 32 000 characters.
