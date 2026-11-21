---
label: Her workspace
icon: file
order: 590
---

# Her workspace

Each assistant has a small **temporary workspace** on the server: a place where her tasks leave a text file for you, such as a script, a
list or a text to paste. It is not storage. Files stay a day, an assistant has at most 30, and one file is at most 100 000 characters of
text.

## How a file gets there

When you ask for something as a file ("find how to install X and write me the install script"), she passes that on to the
[task](../jois/tasks.md) that does the search. The stronger model that runs it can save a file, and tells her in its answer which one.
She is told the names of the files at the end of the result, so she can say "the script is ready".

A task writes at most 5 files. A file of the same name is replaced. The name is a plain file name (letters, digits, dots, dashes,
underscores, spaces; no folders); a task that tries another gets the reason and tries again.

## How she hands it over

Two functions of hers work on the workspace, and she has them only while there is a file in it:

| Function | What it does |
|---|---|
| `list_workspace_files` | tells her the names and sizes of the files |
| `offer_file_to_clipboard` | offers a file to the clipboard of one of your nodes |

The offer needs a node where you switched on [clipboard offers](../node/clipboard.md): without one she does not have the function, and
says so. The server reads the file and sends its text to that node, where you see it and decide (or, if you chose *Allow* there, where it is copied at once). If you have several such nodes she
names the one she means (else the one you are talking through).
