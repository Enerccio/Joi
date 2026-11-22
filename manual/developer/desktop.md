---
label: The desktop app
icon: desktop-download
order: 770
covers:
  - joi-desktop
  - pom.xml
  - .github/workflows/desktop.yml
  - joi-server/src/main/java/com/github/enerccio/joi/api/ApiConfiguration.java
  - joi-server/src/main/java/com/github/enerccio/joi/api/JoiApi.java
  - joi-server/src/main/java/com/github/enerccio/joi/api/JoiApiServlet.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/AccessTokenServiceImpl.java
  - joi-node/src/main/java/com/github/enerccio/joi/node/local
  - joi-node/src/main/java/com/github/enerccio/joi/node/LocalMode.java
  - joi-node/src/main/java/com/github/enerccio/joi/node/NodeInstance.java
verified: 7236ba1
---

# The desktop app

The module `joi-desktop` (the user's guide is [Desktop app](../user/getting-started/desktop-app.md)) packages the server and the node into one
installation for one computer, as Marginalia's desktop app does for Marginalia (`marginalia/marginalia/src/desktop`, `manual/developer/packaging.md`
there). It is not in the default reactor: the root POM's profile `desktop` adds it.

```bash
mvn -B -DskipTests -Pfx,production,desktop package -pl joi-desktop -am
```

`fx` compiles the node's JavaFX app and brings its dependencies and the renderer page (npm; `-Dnpm.mode=--prefer-offline` lets it use the network, the
default `--offline` is the development machine's), `production` builds the frontend bundle into the WAR. Add `-Ddesktop.skipInstaller=true` to leave out
the `.deb`, `.dmg` or `.msi`, `-Ddesktop.appVersion=1.2.3` for jpackage's numeric version (default `1.0.0`; a `-SNAPSHOT` is not accepted). Needs the JDK's `jlink` and
`jpackage` (the JDK that runs Maven), and for the installers dpkg-deb and fakeroot (Linux), hdiutil (macOS) or the WiX Toolset (Windows). jlink and jpackage make a
runtime for the OS and CPU they run on, so build on each target (`desktop.yml` does).

## What is built

All in `joi-desktop/target/desktop`:

| Result | What |
|---|---|
| `joi-<version>/` | the portable folder: `bin/joi(.bat)`, `bin/joi-node(.bat)`, `app/` (the launcher's jar and the jars of the node: JavaFX, Chromium...), `server/` (`jetty-home/` and `joi.war`), `runtime/` (jlink) |
| `package/` | the native app by jpackage: one runtime, two launchers (`Joi`, and `JoiNode` by `--add-launcher`, its properties file written by the POM) |
| `joi-<version>-<os>-<arch>.tar.gz` / `.zip`, `-app.tar.gz` / `-app.zip` | archives of the two above |
| `joi-<version>-<os>-<arch>.deb` / `.dmg` / `.msi` | the installer, made from the app image by `src/main/installer.xml` |

The POM's pieces: `maven-dependency-plugin` copies the WAR (`joi-server`, type war, provided: it only orders the reactor) and jetty-home, and the node's runtime jars
into `desktop/node-jars`; `maven-antrun-plugin` assembles the folder, runs jlink, jpackage, gives the macOS app its `NSMicrophoneUsageDescription` and an ad hoc signature
(`plutil`, `codesign`; the plist change breaks jpackage's signature) and makes the archives. The jlink module list is the server's (as Marginalia's) plus what the node
needs from the class path: found with `jdeps` over the jars of `app/` (`jdk.httpserver` for the renderer page's server, `jdk.jfr` for JavaFX); redo it when a
dependency of the node changes, a module that is missing shows as a `NoClassDefFoundError` in `desktop/logs/node.log` when the node connects.

Both launchers share the class path of jpackage's input folder (every jar of `app/`), which is harmless for the server launcher: it is JDK-only and a different
main class. JavaFX is loaded from the class path, which JavaFX warns about ("classes were loaded from 'unnamed module'") and runs.

The natives of Chromium are `me.friwi:jcef-natives-${jcef.platform}`; the profiles `jcef-<os>-<arch>` of the root POM set `jcef.platform` from the machine (macOS arm64 and
x64, Linux x64 and arm64, Windows x64), and `javafx.version`, `jcef.version`, `jcef.natives.version` live there too. JavaFX's own natives come with its artifacts'
POMs. The node unpacks Chromium into its home on the first start (`fx/Chromium`), nothing is downloaded at run time.

## The launcher

`DesktopLauncher` (JDK only, `com.github.enerccio.joi.desktop`) is the main class of `joi-launcher.jar` and compiles on its own. It:

1. resolves the data folder (`--data`, else `joi.data` / `JOI_DATA`, else `~/.joi`) and sets `joi.data` for the server, makes `<data>/desktop` (`0700`), redirects its output to
   `desktop/logs/joi.log`;
2. finds `server/` next to the launcher jar or above it (`-Djoi.serverDir` overrides), checks the port (`--port`, default 8766; a Joi already answering there with
   `<title>Joi</title>` just opens the browser; another program makes it pick a free port unless the port was given);
3. picks the socket `<data>/desktop/api.sock` (a path over 100 bytes goes to `<tmp>/joi-<user>-<hash>/api.sock`, in a private folder; a folder of that name that is a link or belongs to another user is refused, since `/tmp` may be shared), refuses to start when a server
   answers on it already (the same data folder), removes a stale file, and sets `joi.api.local-socket`;
4. writes the Jetty base (`start.d/joi.ini` with the modules `server,http,unixdomain-http,ee11-deploy,ee11-webapp,ee11-annotations,ee11-websocket-jakarta,ee11-jsp`,
   `webapps/ROOT.xml` for the WAR with a persistent work folder) and starts Jetty in its own JVM through `start.jar`'s `Main` by reflection, with `jetty.http.host`,
   `jetty.http.port` and `jetty.unixdomain.path` as arguments (properties in `start.d` are not read when Jetty is started in-process);
5. shows the tray icon (or a window), waits for the web page, opens the browser, and starts the node unless `--no-node` or the saved choice `node.autostart` (in
   `desktop/launcher.properties`) says no.

**Starting the node** (`startNode`): the node's home is resolved as the node does (`JOI_HOME_DIR`, `JOI_CLIENT`, else `~/.joi-client`) and passed as `--joi-home-dir`, so the
lock `node.lock` that the launcher looks at (`nodeRunning`: a non-blocking lock attempt; the system frees a lock when its process ends, so a crashed node leaves
nothing) is the one the node takes (`NodeInstance.acquire`, in `fx/Launcher`). If it is free, the launcher runs the `JoiNode` binary next to its own executable (found from
`ProcessHandle.current().info().command()`), or, in the portable folder where that is `java`, the runtime's `java -cp app/* ...fx.Launcher`, with `--local <socket> --web <url>`;
the output goes to `desktop/logs/node.log`. Quit destroys the node it started.

## The server side

- `ApiConfiguration.localSocket` (`joi.api.local-socket`) and `isLocalSocket(localAddress, localPort)`: a request on a Unix socket has the socket path as its local address
  and port -1 (checked against a real Jetty `UnixDomainServerConnector`); the path must equal the configured one.
- `ApiCall.local` is set by `JoiApiServlet` from that, nothing the caller sends counts. `JoiApi.local` answers `POST /oauth/local` for a local call only (404 otherwise, 405 for
  another method, 409 `no_user`) with a `LocalToken`.
- `AccessTokenServiceImpl.issueLocal(name, clientId)` makes or renews the user's `LOCAL` token of that `clientId` (`joil_` prefix, SHA-256 of the secret, an hour, no refresh
  token; `clientId` is an extended attribute, so no schema change), and `authenticate` accepts `joil_` tokens regardless of the administrator's switches for tokens and OAuth
  (they are of the computer's socket, not of the network). Access shows them as *This computer*.
- The data folder is the trust boundary: the socket sits in a `0700` folder, and whoever can open it can read the database anyway.

## Tests and what is not tested

`UnixSocketTest` (joi-node) runs `UnixHttp` and `UnixWebSocket` against a server of its own on a Unix socket (bodies of every kind, errors and timeouts, header injection, a
WebSocket exchange with fragments, ping, long frames, the close handshake); `JoiApiTest` covers `/oauth/local` and `isLocalSocket`. Tried by hand on macOS arm64 against the
built portable folder: the server starts with both connectors, the socket answers `409 no_user` before the first account and a token after it, TCP answers 404,
`JoiNodeCli unix:...` logs in and connects its WebSocket through Jetty, the launcher starts the JavaFX node, which signs in over the socket. The Windows and Linux builds are made and
smoke-tested by `desktop.yml` only (the server starts, the socket and the port answer as above, both programs are in the image, the `.deb` installs and starts).
