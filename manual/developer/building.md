---
label: Building
icon: tools
order: 780
covers:
  - pom.xml
  - joi-node/pom.xml
  - CLAUDE.md
verified: ed86d8f
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

## The node

The JavaFX node is behind the `fx` profile (OpenJFX 25, the embedded Chromium from `jcefmaven`, and the renderer page built by Vite):

```bash
mvn -o -Pfx compile exec:exec@run -pl joi-node                           # the JavaFX node
mvn -o -Pfx compile exec:exec@run -pl joi-node -Dnode.args=preview       # her cached body in Chromium, with no server
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
