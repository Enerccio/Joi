---
label: Her workspace
icon: file
order: 590
---

# Her workspace

Each assistant has her **own workspace** on the server: a folder of files, with subfolders, that she keeps between conversations. She
writes notes and texts there, reads them back, and her [tasks](../jois/tasks.md) leave files there for you, such as a script, a list
or a text to paste. Another assistant of yours has another workspace, and nobody else sees it.

The administrator sets how much fits (see below).

## What she can do with it

She has the file functions for it, always, without asking you: she lists, reads, writes, appends to, edits (a text replaced, or a
regular expression), searches (`grep_files`), moves, copies, merges and deletes files, and unpacks a zip. A path looks like
`/notes/plot.md`.

When you want a text you can paste, she can also offer a file to the clipboard of one of your nodes (`offer_file_to_clipboard`). That
needs a node where you switched on [clipboard offers](../node/clipboard.md): without one she is told it fails, and says so. The server
reads the file and sends its text to that node, where you see it and decide (or, if you chose *Allow* there, where it is copied at
once). If you have several such nodes she names the one she means (else the one you are talking through).

## Images

When you send her an image in the chat, she can **save it into her workspace** (`save_chat_image`): the one in your message, or an earlier one. So you can send a picture, and in a later message ask her to keep it. She can list the images you sent
(`list_chat_images`: the newest first, with the words they came with) and choose one by its number. The file is a JPEG, as the server kept it (large images were scaled down when you sent them), and its name ends in `.jpg`.

If her chat model takes images (it is marked *Multimodal* in the Models tab), she can also **look at** an image of her workspace (`view_image`), for example one she saved or one you uploaded in the Files tab. A model that does not take images is not offered the
function. Her tasks can do the same when the model that runs them takes images.

## How a task writes a file

When you ask for something as a file ("find how to install X and write me the install script"), she passes that on to the task that
does the search. The stronger model that runs it can save the file in her workspace, and tells her in its answer which ones: she is
told the paths at the end of the result, so she can say "the script is ready". A file of the same path is replaced.

## The Files tab

The Joi dialog has a **Files** tab with the workspace: browse the folders, read a text, download a file, upload a text file, an image or a zip, make a folder, delete
what you do not want, or clear it all. What you put there she can read, and what she writes appears there.

## The limits (administrators)

**Administration → App settings → Workspace of an assistant** holds the limits, the same for every assistant: the largest file, the
whole workspace, the number of files, the largest image and the largest archive (a zip she unpacks). A field left empty uses the
default (1 MB a file, 20 MB, 500 files, 5 MB, 10 MB). A write that would pass a limit is refused with the reason, and she or the task
tries something smaller.

Files of an assistant that was deleted for good are not removed from the server's disk.
