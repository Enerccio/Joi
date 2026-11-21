---
label: Building
icon: tools
order: 780
covers:
  - pom.xml
  - joi-node/pom.xml
  - CLAUDE.md
  - Dockerfile
  - docker-compose.yml
  - docker-entrypoint.sh
verified: d71f903
---

# Building

JDK 25 or newer and Maven 3.9. Clone with `git clone --recurse-submodules` (or `git submodule update --init`). Where `mvn` runs on an older
JDK, set `JAVA_HOME` to a 25+ one. Build offline (`-o`) when the dependencies are in `~/.m2`.

## The reactor

The root is `joi-parent`: `marginalia/marginalia/platform` (from the submodule), `joi-protocol`, `joi-server` and `joi-node`.

```bash
mvn install -DskipTests -pl marginalia/marginalia/platform   # the platform and its test-jar into ~/.m2, after each submodule bump
mvn -N install && mvn install -DskipTests -pl joi-protocol   # the parent POM and the protocol (needed for -pl joi-server alone)
mvn jetty:run -pl joi-server                                  # dev mode on http://localhost:8080, data in ~/.joi (JOI_DATA)
mvn package -pl joi-server -Pproduction                       # the WAR with the production frontend bundle
mvn test -pl joi-server -Dtest=JoiContextTest                 # the classes you touched
mvn test -pl joi-node                                         # the node's core and its terminal client
```

`-pl joi-server -am` also builds the platform first; its tests are skipped by `-Dtest=...` with
`-Dsurefire.failIfNoSpecifiedTests=false`. The platform's own tests belong to Marginalia: do not run them from here.

In `jetty:run` the API accepts calls without credentials (`joi.api.no-auth`, set in the pom) and the frontend is built in development mode. The
production profile builds the Vite bundle (npm install, Vite).

## Compile-time weaving

The UI classes are created with `new` and are `@Configurable`: the build uses AspectJ compile-time weaving, so injected fields are
filled. Compiling with plain `javac` leaves them `null`.

## The image

The `Dockerfile` in the root builds the server, nothing else (the node runs on the user's machines): a Maven stage builds the platform from
the submodule, `joi-protocol` and `joi-server` into the WAR with the production frontend bundle (`-Pproduction -pl joi-server -am`), and a Jetty 12
stage runs it, started by `docker-entrypoint.sh` (it hands the data folder to the `jetty` user, then drops root). The data folder is `JOI_DATA`
(`/var/joi/data`, the volume of `docker-compose.yml`), the health check is `/health`. The image has ffmpeg and headless Blender 5.2.1 with the VRM add-on (the official x86-64 build, checked against its sha256, in
`/opt/blender`, the add-on in `BLENDER_USER_RESOURCE`; on another architecture it is left out with a warning, since blender.org has no ARM Linux build;
`--build-arg WITH_FFMPEG=false` / `WITH_BLENDER=false` leave them out). The user's guide is [Server (Docker)](../user/getting-started/docker-server.md).

```bash
docker compose up -d --build
```

## The extensions

`joi-extensions/` holds the model providers that come with Joi (`anthropic`, `google-genai`, `grok`), OSGi bundles built against the platform only
(see [Extensions](extensions.md)). They are in the reactor but not in the server's image: build them with Maven, or in Docker (needs the Dockerfile's
`extensions` stage, the platform comes from the server's build):

```bash
mvn -o package -pl joi-extensions/anthropic,joi-extensions/google-genai,joi-extensions/grok   # target/<name>-<version>.jar
docker compose -f joi-extensions/docker-compose.yml build
docker compose -f joi-extensions/docker-compose.yml run --rm extensions                       # the JARs in joi-extensions/build
```

Then copy the JARs into the `extensions` folder of the data folder (`./data/extensions` with the compose file) and restart the server, or load them
in Administration → Extensions.

## The node

The JavaFX node is behind the `fx` profile (OpenJFX 25, the embedded Chromium from `jcefmaven`, and the renderer page built by Vite):

```bash
mvn -o -Pfx compile exec:exec@run -pl joi-node                           # the JavaFX node
mvn -o -Pfx compile exec:exec@run -pl joi-node -Dnode.args=preview       # her cached body in Chromium, with no server
mvn -o -Pfx compile exec:exec@run -pl joi-node -Dnode.args="--joi-home-dir ~/.joi-dev"   # a node with a home of its own (or JOI_HOME_DIR)
```

Run it with `exec:exec@run` (`fx/Launcher`), not `javafx:run`, which leaves the Chromium jars off the class path. The renderer page is
`joi-node/src/main/renderer`; the server's `joi-model-viewer.js` is copied in at build time.

## Schema changes

Every entity change has a Flyway migration in `joi-server/src/main/resources/migration` (Hibernate only validates), including the
platform's tables. While Joi is not running in production the first migration, `V1__platform.sql`, is edited in place instead of adding a
version. A development database built from an older V1 fails Flyway's checksum: do not delete it, upgrade it with
`python3 tools/upgrade_dev_db.py` (server stopped; it backs the database up, rebuilds it from the current V1, copies the data by shared
columns and sets the checksum; `--dry-run` only reports).

## Blender and ffmpeg

Conversion needs Blender with the VRM add-on (`joi.blender` / `JOI_BLENDER`, else Blender.app on macOS, else `blender` on the path); speech
needs ffmpeg (`JOI_FFMPEG`, else the path). Tests do not need either.

## The manual

`tools/retype_build.sh` builds this manual into `docs/` (needs `retype`). See
[Screenshots and the manual](screenshots-and-manual.md).
