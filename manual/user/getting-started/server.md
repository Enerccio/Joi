---
label: Install the server
icon: server
order: 990
---

# Install the server

For one computer there is a simpler way: the [desktop app](desktop-app.md), an installer with the server, the node and Java in it. This page is
for a server you build or run yourself.

## Requirements

- JDK 25 or newer. Set `JAVA_HOME` to it for Maven.
- Maven 3.9 and Git, to build from source.
- Network access for the first build, which fetches the web UI's packages for the production bundle.

## Get the source and build

Clone with the submodules, because Joi builds on the platform of Marginalia, which is a submodule:

```bash
git clone --recurse-submodules https://github.com/Enerccio/Joi
cd Joi
mvn install -DskipTests -pl marginalia/marginalia/platform
mvn -N install && mvn install -DskipTests -pl joi-protocol
```

## Start it

For a first look, run the server in development mode:

```bash
mvn jetty:run -pl joi-server
```

It listens on `http://localhost:8080`. This mode lets API calls without credentials act as the first administrator, so a
node on the same machine connects with no token. Do not use it on a network.

For a real installation use [Docker](docker-server.md), or build the WAR with the production frontend bundle and put it in any servlet container:

```bash
mvn package -pl joi-server -Pproduction
```

## The data folder

Everything the server keeps is in one folder: the database (SQLite), the key that protects stored secrets (`secret.key`), and
the files you upload (models, clips, images, speech). It is `~/.joi` by default. Set `JOI_DATA` (or the system property
`joi.data`) to use another folder. Back it up as a whole while the server is stopped.

The Administration page has Database Backups, Cleanup and Resource Files for looking after it.

## Settings

Every setting can be given as an environment variable or a `-D` system property of the same name: `joi.data` is
`JOI_DATA`.

| Setting | Meaning |
|---|---|
| `joi.data` | the data folder (default `~/.joi`) |
| `joi.allow-persistent-login` | whether the login can be remembered (default on); `joi.persistent-login-ttl` is how long, in seconds (30 days) |
| `joi.trusted-proxies` | the addresses of reverse proxies whose forwarded client address is believed; empty means the header is never read |
| `joi.api.public-url` | the address the server is reached at from outside, used for the links of the device sign-in; empty uses the address of the call |
| `joi.api.no-auth`, `joi.api.no-auth-user` | API calls without credentials act as this user (development only; the first administrator when no user is named) |
| `joi.blender` (`JOI_BLENDER`) | the Blender executable, else Blender.app on a Mac, else `blender` on the path |
| `JOI_FFMPEG` | the ffmpeg executable for speech, else the path |

## Behind a proxy

Put the server behind a reverse proxy that terminates TLS. Set `joi.trusted-proxies` to the proxy's address so the log-in
and the access list see the real client, and `joi.api.public-url` to the public address so that the device sign-in shows
links that work from outside. The node connects with a WebSocket on the same address, so the proxy must pass WebSocket
upgrades.

## Ports

The server uses the one HTTP port of its container for the web UI, the API (`/api/v1`) and the node WebSocket
(`/api/v1/node`).
