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

The device then gets an access token (an hour by default) and a refresh token (90 days; each refresh gives a new pair). Revoke the
device in the list to cut it off.

## What the administrator allows

Under **Administration → API access** the administrator chooses whether **Tokens** and **OAuth (devices)** are offered, and the
lifetime of OAuth access and refresh tokens. Turning a way off stops its tokens and devices at once; turning it back on brings
them back.

## No authentication

A server started with `joi.api.no-auth` (as `mvn jetty:run` does) answers calls without credentials as the user named by
`joi.api.no-auth-user`, or the first administrator. The page says so. It is only for development and localhost: a credential
that is sent must still be valid.
