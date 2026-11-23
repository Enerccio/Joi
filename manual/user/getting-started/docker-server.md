---
label: Server (Docker)
icon: container
order: 985
---

# Server (Docker)

The easiest way to run Joi for real: a container with the server, its data in a folder of the host, and a reverse proxy in
front of it that terminates HTTPS. Your nodes (on your desktop, your laptop) connect to it from wherever you are.

!!!warning Before the first release
Joi's database is not frozen yet: an update that changes its structure can refuse to start on a database made by an older
version. Do not keep data you cannot recreate on a server until the first release says otherwise.
!!!

## Requirements

- [Docker](https://docs.docker.com/get-docker/) with Docker Compose (on a Mac: Docker Desktop, or Colima with the `docker` and
  `docker-compose` packages).
- About 1 GB of memory for the server, and about 4 GB more while Blender converts a model. The text, speech and image models run
  elsewhere: they are servers you point Joi at.
- An x86-64 host (a PC, a rented server) for the image with Blender, see [Converting models](#converting-models).
- For access from outside your home network: a domain and a reverse proxy with HTTPS (see [below](#https-and-reverse-proxy)).

## Install

```sh
git clone --recurse-submodules https://github.com/Enerccio/Joi.git
cd Joi
docker compose up -d
```

The first `docker compose up` builds the image from source, which takes several minutes (it downloads the dependencies and
the packages of the web UI). The server then listens on port **8080** of the host, **on that machine only**. Open
`http://localhost:8080` and make the administrator account (see [First start](first-start.md)).

## Configuration

Everything is set in `docker-compose.yml`:

```yaml
services:
  joi:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: joi-server
    restart: unless-stopped
    ports:
      - "127.0.0.1:8080:8080"
    volumes:
      - ./data:/var/joi/data
    environment:
      - JAVA_TOOL_OPTIONS=-Xms256m -Xmx1g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/joi/data/heapdump.hprof
      - JOI_TRUSTED_PROXIES=172.16.0.0/12
```

| Setting | What it does |
|---|---|
| `ports` | `"127.0.0.1:8080:8080"` publishes the server on port 8080 of the host for a reverse proxy on the same host. Use `"8080:8080"` to reach it from other machines of your network without a proxy: never over the internet, see below. |
| `volumes` | `./data` holds everything the server keeps: the database, the key that protects your stored secrets (`secret.key`), your models, clips, images and speech. Keep it, and back it up (see [Backups](#backups)). It is created on the first start; on start the container makes it owned by the user the server runs as inside the container (`jetty`), so its files belong to that user's ID on the host. |
| `-Xmx1g` | Maximum memory. Raise it (for example `-Xmx2g`) when many models and clips are open at once. |

After changing the file, apply it with `docker compose up -d`.

### Environment variables

The server's settings are environment variables of the service, next to `JAVA_TOOL_OPTIONS`:

| Variable | Default | What it does |
|---|---|---|
| `JOI_DATA` | `/var/joi/data` in the image | The data folder. Change it only with a matching volume (`./data:/your/folder`); the container fixes the owner of this folder on start. |
| `JOI_TRUSTED_PROXIES` | empty | The reverse proxies whose `X-Forwarded-For` header is believed, see [below](#https-and-reverse-proxy). |
| `JOI_API_PUBLIC_URL` | the address of the call | The address the server is reached at from outside (`https://joi.example.com`): the links of the device sign-in of a node are made from it. Set it behind a proxy. |
| `JOI_ALLOW_PERSISTENT_LOGIN` | `true` | `false` hides *Save login*. `JOI_PERSISTENT_LOGIN_TTL` is how long a saved login lasts, in seconds (30 days). |
| `JOI_COOKIE_NAMESPACE` | `joi` | Starts the names of the saved-login cookies. Cookies are kept per host, not per port, so Joi and Marginalia (or two Joi servers) on one host would read and delete each other's saved logins with the same names. Give each server its own. |
| `JOI_SESSION_COOKIE` | `JOISESSIONID` | The name of the session cookie, for the same reason (the usual `JSESSIONID` is shared by every server on the host, and each replaces the other's session). Change it only to run a second Joi on the host. |
| `JOI_FFMPEG` | the program on the path | The ffmpeg program for speech (the image has one). |
| `JOI_BLENDER` | `blender` on the path (the image has it) | The Blender program for converting models, see [below](#converting-models). |
| `JOI_API_NO_AUTH` | `false` | **Never set it on a server.** API calls without credentials then act as the first administrator. It is for `mvn jetty:run` on your own machine. |

The same settings can be given as Java options (`-Djoi.trusted-proxies=...`), which take precedence over the variables. A value
that does not fit (text for the number of seconds) stops the server from starting; the reason is in the log.

## HTTPS and reverse proxy

!!!danger Don't use plain HTTP over the internet
Passwords, login cookies and the tokens of your nodes are sent with every request. Over plain HTTP anyone on the way can read
them. Put the server behind a reverse proxy with HTTPS.
!!!

The server updates the page and talks to nodes over **WebSocket** connections, and answers a chat as a stream, so the proxy must
forward WebSocket upgrades, not buffer the answers and let long requests live. Your models and clips can be large: allow uploads
of that size. Examples for `joi.example.com`:

+++ Caddy
Caddy gets the certificate and forwards WebSockets and streams automatically.

```
joi.example.com {
    request_body {
        max_size 1GB
    }
    reverse_proxy localhost:8080
}
```
+++ nginx
```nginx
server {
    listen 443 ssl;
    server_name joi.example.com;

    ssl_certificate     /etc/letsencrypt/live/joi.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/joi.example.com/privkey.pem;

    client_max_body_size 1g;   # uploads of models, clips, backups

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;     # a chat answer is a stream
        proxy_read_timeout 1h;   # the connection of a node and long answers stay open
    }
}
```
+++

Then set `JOI_API_PUBLIC_URL=https://joi.example.com` in `docker-compose.yml` and give your nodes that address.

Failed logins are throttled per user and per client address. The server believes the `X-Forwarded-For` header only of a proxy
you name: without it, everybody appears to come from the proxy's address. Keep the header in the proxy configuration (as
above) and set `JOI_TRUSTED_PROXIES` to the address or range of the proxy. A proxy on the Docker host reaches the container
from the Docker network, normally `172.16.0.0/12` (the value in the file); several entries are separated by commas.

## Models on the same machine

Inside the container, `localhost` is the container itself, not the server. To use a model server on the Docker host (llama.cpp,
Ollama, LM Studio, ComfyUI for speech...), use the address `http://host.docker.internal:<port>` in the model's settings. On Linux,
add this to the service in `docker-compose.yml`:

```yaml
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

The model server must also listen on an address the container can reach, not only on `127.0.0.1`.

## Converting models

The image has **headless Blender** with the *VRM format* add-on, which converts your 3D models and animation clips, and **ffmpeg**
for speech. Blender is the official build from blender.org (checked against its published sha256), installed in `/opt/blender`
and found by the server on the path, so `JOI_BLENDER` is not needed. Blender works on one file at a time and needs memory: give
the container what it needs (about 4 GB more than the server).

blender.org builds Blender for Linux on **x86-64 only**. On an ARM host (a Mac with Apple silicon, a Raspberry Pi) the image is
made without it, with a warning in the build log, and a conversion then fails with "Blender was not found". You can still upload
a finished `.joim` (a processed model in one file), converted on another Joi server of yours. To have Blender in the image on an
ARM host, build it for x86-64 (`platform: linux/amd64` in `docker-compose.yml`, emulated and slow).

Leave Blender (about 1 GB) or ffmpeg out with `--build-arg WITH_BLENDER=false` or `--build-arg WITH_FFMPEG=false` (in the compose
file: `build: args:`). `BLENDER_VERSION` is the version of Blender, 5.2.1 now.

## Backups

Everything is in the data folder, and the key in it (`secret.key`) is what decrypts your stored secrets, so a database without
its key loses them: back up the whole folder, always together.

- **The database:** the Administration page, *Database Backups*, makes copies by hand or on a schedule (a cron expression, and how
  many to keep). They are kept in the data folder, so they are not a backup of the machine: copy `data` off it.
- **The whole folder:** stop the server (`docker compose stop`), copy `data` (the files of the database are only consistent while
  it is stopped), start it again.
- **Try a restore** before you need one: copy a backup of `data` to another machine, start a server on it with the same
  `docker-compose.yml`, and check that you can log in and that your assistant is there.

## Updating

```sh
git pull --recurse-submodules
docker compose up -d --build
```

The database is upgraded automatically when the new version starts. Make a [backup](#backups) first.

### Automatic updates

`rebuild.sh` (Linux, macOS) and `rebuild.bat` (Windows) in the repository root do the update for you; run them by hand or from a
cron job or a scheduled task. They fetch the remote of the branch the checkout is on and do nothing when there is nothing new
(`--force` rebuilds and restarts anyway, for example after changing `docker-compose.yml`). Otherwise they `git pull`, update the
Marginalia submodule, build the [extensions](#extensions) and the image **while Joi keeps running**, then stop the container, copy the
extension JARs into `data/extensions` (replacing the installed ones) and start the new version. If a step fails, the script stops with
a message; when it is the pull or a build, the old version is still running, and the next run builds again.

`--stop-first` stops the container before the build, for a server that does not have the memory for the application and the build
together; Joi is then down for the whole build.

Make a [backup](#backups) first when the update changes the database.

## Health

The image has a Docker health check: `docker ps` shows `(health: starting)` while the server starts (up to two minutes), then
`(healthy)`. It is `unhealthy` when `http://localhost:8080/health` stops answering with `200`. That address needs no login, so a
monitoring tool can use it too (through the proxy: `https://joi.example.com/health`). It answers `200` and `{"status":"UP"}` when
the server runs and its database answers, and `503` otherwise; it shows nothing else.

## Extensions

The model providers Anthropic Claude, Google Gemini and xAI Grok are [extensions](../extensions/index.md), and they are not in the server's image: they
are loaded from the `extensions` folder of the data folder, which is the volume. Build them in a container (no Java or Maven needed on the server)
and copy them in:

```sh
docker compose -f joi-extensions/docker-compose.yml build
docker compose -f joi-extensions/docker-compose.yml run --rm extensions
cp joi-extensions/build/*.jar data/extensions/
docker compose restart joi
```

Or load a JAR in Administration → Extensions, which needs no restart. Build them from the same checkout as the server: an extension is built for
one version. `rebuild.sh` does the copying for you, so on a server that uses it the extensions are always updated together with the server.

## Logs

```sh
docker compose logs -f joi
```
