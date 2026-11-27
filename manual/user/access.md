---
label: Access tokens
icon: key
order: 450
---

# Access tokens

The server's API is how nodes and your own scripts use it. They sign in with a token or by pairing. This is under **User Settings →
Access**, and the API is at `<server>/api/v1`.

![The Access tab](../../images/settings-access.png)

The list shows your tokens and devices: when each was created, when it expires, when it was last used, with **Revoke**.

## A token

**Create token** makes one: a name and an optional expiry. The token (it starts with `joi_`) is shown once, so copy it then. Give
it to a client as a bearer token. Revoking it stops it at once.

### Administration from a script

An administrator's **Create token** also has **Access**: *Update the installation* and *Manage the extensions*. A token with them may use the
administration calls of the API, for example to install a new build of an extension from a script or a CI job:

```bash
curl -X PUT --data-binary @joi-grok.jar -H "Authorization: Bearer $JOI_TOKEN" https://joi.example/api/v1/admin/extensions/joi-grok.jar
```

| Access | Calls |
|---|---|
| Update the installation | `GET /api/v1/admin/update` (the status, as [Updates](administration/updates.md) shows it), `POST` the same to ask for an update, `GET .../update/log` |
| Manage the extensions | `GET /api/v1/admin/extensions` and `.../{jar}`, `PUT .../{jar}` with the JAR as the body (installs it, or replaces the one of that name), `DELETE .../{jar}`, `POST .../{jar}/stop` and `.../start`, `GET .../{jar}/log` |

They work only while the owner of the token is an administrator: take the role away and the token keeps only what any token does. The
**Access** column of the list shows them. A device, the phone and this computer never have them. An extension that does not fit this
version of Joi is refused (`422`) with the report, and nothing changes. All of it is in the API's description (`openapi.yaml`).

## Connecting a device

A device (such as the node) can pair with the **OAuth device grant** instead:

1. The device shows a code like `WDJB-MJHT` and a link.
2. Open the link after you sign in, or press **Connect a device** here and type the code.
3. Press **Approve** (or **Deny**).

## Pairing a phone with its camera

Press **Pair a phone** (next to **Connect a device**): the window shows a QR code. Scan it with the phone's camera; it opens the Joi app, which
connects to this server and is approved here by itself, so there is no code to type. Keep the window open until the phone says it is connected.
The code carries the address of this server: if the window shows `localhost`, change it to the address the phone reaches (the computer's
address on your network, or the server's public one). The code works once, and only while the window is open (ten minutes at most).

The device then gets an access token (an hour by default) and a refresh token (90 days; each refresh gives a new pair). Revoke the
device in the list to cut it off.

### Sending her a photo from the phone

When she can look at images (see [Chats](jois/chats.md#showing-her-an-image)), the chat of the phone app has a button next to the field: **Take a photo**
(the app asks for the camera the first time) or **Choose from the library**. Up to four photos wait as small pictures with a button to take one
away, and go with your words or alone. The phone scales a photo before it sends it, so it uploads less. Without a model of hers that takes images the
button is not there.

## This computer

A node on the same computer as the server needs no token and no pairing when the server offers a **local socket**: the
[desktop app](getting-started/desktop-app.md) does. The node then asks the server for a token over the socket
(`POST /oauth/local`, which only works there, never over the network) and asks again when it expires. Such tokens are listed here as
**This computer**, one per node, and revoking one makes the node ask again. The web page keeps its login.

## What the administrator allows

Under **Administration → API access** the administrator chooses whether **Tokens** and **OAuth (devices)** are offered, and the
lifetime of OAuth access and refresh tokens. Turning a way off stops its tokens and devices at once; turning it back on brings
them back.

## No authentication

A server started with `joi.api.no-auth` (as `mvn jetty:run` does) answers calls without credentials as the user named by
`joi.api.no-auth-user`, or the first administrator. The page says so. It is only for development and localhost: a credential
that is sent must still be valid.
