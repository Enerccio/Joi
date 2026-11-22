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
