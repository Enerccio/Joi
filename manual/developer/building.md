---
label: Building
icon: tools
order: 780
covers:
  - pom.xml
  - joi-node/pom.xml
  - joi-desktop/pom.xml
  - joi-protocol/pom.xml
  - joi-ios/pom.xml
  - .github/workflows/desktop.yml
  - CLAUDE.md
  - Dockerfile
  - docker-compose.yml
  - docker-entrypoint.sh
  - tools/version_from_tag.sh
  - .github/workflows/server.yml
  - .github/workflows/test.yml
  - .github/dependabot.yml
verified: d150187
---

# Building

JDK 25 or newer and Maven 3.9. Clone with `git clone --recurse-submodules` (or `git submodule update --init`). Where `mvn` runs on an older
JDK, set `JAVA_HOME` to a 25+ one. Maven downloads the dependencies on the first build; `-o` (offline) works once they are in `~/.m2`.

## The reactor

The root is `joi-parent`: `marginalia/marginalia/platform` (from the submodule), `joi-protocol`, `joi-server` and `joi-node`.

```bash
git submodule update --init                                   # the Marginalia submodule, if not cloned with it
mvn -N install                                                # Joi's parent POM into ~/.m2
mvn -N install -f marginalia/pom.xml                          # Marginalia's parent POM: the platform's POM refers to it
mvn install -DskipTests -pl marginalia/marginalia/platform,joi-protocol   # the platform (and its test-jar) and the protocol, after each submodule bump
mvn jetty:run -pl joi-server                                  # dev mode on http://localhost:8080, data in ~/.joi (JOI_DATA)
mvn package -pl joi-server -Pproduction                       # the WAR with the production frontend bundle
mvn test -pl joi-server -Dtest=JoiContextTest                 # the classes you touched
mvn test -pl joi-node                                         # the node's core and its terminal client
```

On a new machine the two parent POMs come first: without Marginalia's (`marginalia-parent`) in `~/.m2`, Maven cannot
resolve the installed platform. The workflows do the same.

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
mvn package -pl joi-extensions/anthropic,joi-extensions/google-genai,joi-extensions/grok   # target/<name>-<version>.jar
docker compose -f joi-extensions/docker-compose.yml build
docker compose -f joi-extensions/docker-compose.yml run --rm extensions                       # the JARs in joi-extensions/build
```

Then copy the JARs into the `extensions` folder of the data folder (`./data/extensions` with the compose file) and restart the server, or load them
in Administration → Extensions.

## GitHub builds

Four workflows in `.github/workflows` (as Marginalia's) and Dependabot. All check out with `submodules: recursive`.

| Workflow | When | What |
|---|---|---|
| `test.yml` | every push and pull request | Builds the platform (no tests of its own) and the protocol, then runs the tests of `joi-protocol`, `joi-server` and `joi-node` (not the JavaFX part). Reports are an artifact when it fails. |
| `server.yml` | master, `v*` tags, pull requests that touch the server, extensions or Docker files | The WAR (`-Pproduction`) and the three extension JARs as the artifact `joi-server`; the Docker image for `linux/amd64` and `linux/arm64`, each built on a native runner and smoke-tested (`/health` answers 200) before it is pushed. |
| `desktop.yml` | master, `v*` tags, pull requests that touch the desktop app, node, server or protocol | The desktop app (`-Pfx,production,desktop`, see [The desktop app](desktop.md)) on Linux x64 and arm64, Windows x64 and macOS arm64 and x64, with its installers, smoke-tested (the server starts, the API socket answers `POST /oauth/local` with 409 while the port answers 404, both programs and the `.deb` are there). The files are an artifact per platform and, for a `v*` tag, attached to the release. |
| `manual.yml` | master, pull requests that touch the manual | The manual to GitHub Pages. |

The image goes to `ghcr.io/<owner>/joi`: `edge` from master, and `<version>`, `<major>.<minor>` and `latest` from a `v*` tag. Pull requests only
build. A `v*` tag also attaches `joi-server-<version>.war` and `joi-extension-<name>-<version>.jar` to its GitHub release. The image holds no
extensions, as above: they are loaded from the data folder. The `linux/arm64` image has no Blender.

The release version is the tag: `tools/version_from_tag.sh` rewrites Joi's own POMs (not the platform's, whose version is Marginalia's) to
`1.2.3` for `v1.2.3` before every build. Other builds keep the version of `pom.xml`. To try it on a copy of the POMs:
`JOI_ROOT=<copy> GITHUB_REF=refs/tags/v1.2.3 tools/version_from_tag.sh`. `.github/dependabot.yml` opens grouped weekly pull requests for Maven,
the actions and the Docker base images. `test.yml` and `server.yml` do not build the node's JavaFX app; `desktop.yml` does, on each platform, with the natives of that platform
(`jcef.platform`, set by the profiles of the root POM).

## The node

The JavaFX node is behind the `fx` profile (OpenJFX 25, the embedded Chromium from `jcefmaven`, and the renderer page built by Vite):

```bash
mvn -Pfx compile exec:exec@run -pl joi-node                           # the JavaFX node
mvn -Pfx compile exec:exec@run -pl joi-node -Dnode.args=preview       # her cached body in Chromium, with no server
mvn -Pfx compile exec:exec@run -pl joi-node -Dnode.args="--joi-home-dir ~/.joi-dev"   # a node with a home of its own (or JOI_HOME_DIR)
```

Run it with `exec:exec@run` (`fx/Launcher`), not `javafx:run`, which leaves the Chromium jars off the class path. The renderer page is
`joi-node/src/main/renderer`; the server's `joi-model-viewer.js` is copied in at build time.

## The desktop app

`mvn -Pfx,production,desktop package -pl joi-desktop -am` builds the server and the node into one installation, with a tray icon and a socket for the node: see
[The desktop app](desktop.md). The `fx` profile's npm step uses npm's cache first and the network for what is missing (`npm.mode`, default `--prefer-offline`; `--offline` there stops it from downloading, which fails with `ENOTCACHED` on a machine whose cache is empty), the natives of Chromium follow the machine (`jcef.platform`).

## Schema changes

Every entity change has a Flyway migration in `joi-server/src/main/resources/migration` (Hibernate only validates), including the
platform's tables. While Joi is not running in production the first migration, `V1__platform.sql`, is edited in place instead of adding a
version. A development database built from an older V1 fails Flyway's checksum: do not delete it, upgrade it with
`python3 tools/upgrade_dev_db.py` (server stopped; it backs the database up, rebuilds it from the current V1, copies the data by shared
columns and sets the checksum; `--dry-run` only reports).

## The iOS app

`mvn -Pswift,ios verify -pl joi-ios -am` builds the app for the Simulator (Xcode and XcodeGen, macOS only; the profile `ios` adds the module, `swift` writes the
protocol's Swift package); it also builds the node's renderer page with npm (like the node) into `joi-ios/App/Renderer`. The Simulator build is signed ad hoc (the Keychain needs a signature's entitlements). See [The iOS app](ios-app.md#building-with-maven).

## The protocol in Swift

A change of a JSON Schema (or of `asyncapi.yaml`) also changes the generated Swift package of the iOS app, which is in Git: run
`mvn -Pswift process-test-classes -pl joi-protocol` and commit what it writes, else `SwiftGeneratorTest` fails. See
[The protocol](protocol.md#the-swift-package).

## Blender and ffmpeg

Conversion needs Blender with the VRM add-on (`joi.blender` / `JOI_BLENDER`, else Blender.app on macOS, else `blender` on the path); speech
needs ffmpeg (`JOI_FFMPEG`, else the path). Tests do not need either.

## The manual

`tools/retype_build.sh` builds this manual into `docs/` (needs `retype`). See
[Screenshots and the manual](screenshots-and-manual.md).
