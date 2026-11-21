# Stages: builder (the platform from the Marginalia submodule, the protocol and the server, built into the WAR with the
# production frontend bundle) and runtime (the image, the default target, so it must stay the last stage). The node is
# not part of the image: it runs on the user's machines. Build from the repository root, with the submodule checked out
# (git clone --recurse-submodules, or git submodule update --init).
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

# Cache dependencies. The POMs of the reactor come first (the root POM lists joi-node, so its POM must be there too, its
# sources are not). The platform's parent (marginalia/pom.xml) and the root POM are installed, then the platform's
# dependencies are fetched; what only the server needs (the build plugins, Vaadin's) is downloaded by the build below
COPY pom.xml ./pom.xml
COPY marginalia/pom.xml ./marginalia/pom.xml
COPY marginalia/marginalia/platform/pom.xml ./marginalia/marginalia/platform/pom.xml
COPY joi-protocol/pom.xml ./joi-protocol/pom.xml
COPY joi-server/pom.xml ./joi-server/pom.xml
COPY joi-node/pom.xml ./joi-node/pom.xml
RUN mvn install -N -B -f pom.xml \
    && mvn install -N -B -f marginalia/pom.xml \
    && mvn dependency:go-offline -B -f marginalia/marginalia/platform/pom.xml

# The sources, then the WAR (the platform and the protocol are built first, -am). Tests are skipped; run them outside.
# The frontend tooling (node_modules, Node.js in ~/.vaadin, the npm cache) is deleted in the same step, so it never gets
# into a layer.
COPY marginalia/marginalia/platform/src ./marginalia/marginalia/platform/src
COPY joi-protocol/src ./joi-protocol/src
COPY joi-server/src ./joi-server/src
RUN mvn package -B -DskipTests -Pproduction -pl joi-server -am \
    && mkdir /war && cp joi-server/target/joi-server-*.war /war/ROOT.war \
    && rm -rf joi-server/node_modules "$HOME/.vaadin" "$HOME/.npm" "$HOME/.cache"

FROM jetty:12-jdk25-eclipse-temurin AS runtime

# first, before anything that changes with the source (the WAR): this layer stays cached over normal deploys
USER root
# ffmpeg is optional: the server uses it for the speech of Higgs Audio (flac) and for mp3 of a wav, and works without
# it. Build without it with --build-arg WITH_FFMPEG=false
ARG WITH_FFMPEG=true
RUN if [ "$WITH_FFMPEG" = "true" ]; then \
        apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*; \
    fi

# Blender, headless, with the "VRM format" add-on: the server converts the user's 3D models and animation clips with it (the
# scripts convert_avatar.py and convert_animation.py run in `blender -b`). The official build is the one from blender.org
# (checked against the sha256 it publishes), version BLENDER_VERSION: use the version the scripts were tried with. It is
# built for x86_64 only: on another architecture (an Apple silicon host) the image is made without it and says so;
# build for x86_64 there with --platform linux/amd64 (emulated, slow). Without it the conversions fail and an upload of
# finished models (.joim) still works. The add-on is installed from extensions.blender.org into the user folder of
# Blender (BLENDER_USER_RESOURCE), which the jetty user owns. Build without Blender (about 1 GB smaller) with
# --build-arg WITH_BLENDER=false
ARG WITH_BLENDER=true
ARG BLENDER_VERSION=5.2.1
ENV BLENDER_USER_RESOURCE=/opt/blender-user
RUN if [ "$WITH_BLENDER" != "true" ]; then \
        echo "Blender is left out (WITH_BLENDER=$WITH_BLENDER)"; \
    elif [ "$(uname -m)" != "x86_64" ]; then \
        echo "WARNING: blender.org builds Blender for Linux on x86_64 only, this image is $(uname -m): it has no Blender"; \
    else \
        set -e; \
        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
            libx11-6 libxext6 libxi6 libxxf86vm1 libxfixes3 libxrender1 libxkbcommon0 libsm6 libice6 \
            libgl1 libegl1 libwayland-client0 libwayland-cursor0 libwayland-egl1 libdecor-0-0 \
        && rm -rf /var/lib/apt/lists/*; \
        series="${BLENDER_VERSION%.*}"; \
        base="https://download.blender.org/release/Blender${series}"; \
        archive="blender-${BLENDER_VERSION}-linux-x64.tar.xz"; \
        curl -fsSL -o "/tmp/${archive}" "${base}/${archive}"; \
        expected="$(curl -fsSL "${base}/blender-${BLENDER_VERSION}.sha256" | awk -v f="${archive}" '$2 == f {print $1}')"; \
        test -n "$expected"; \
        echo "${expected}  /tmp/${archive}" | sha256sum -c -; \
        mkdir /opt/blender && tar -xJf "/tmp/${archive}" -C /opt/blender --strip-components=1 && rm "/tmp/${archive}"; \
        ln -s /opt/blender/blender /usr/local/bin/blender; \
        if ldd /opt/blender/blender | grep "not found"; then echo "Blender misses libraries"; exit 1; fi; \
        mkdir -p "$BLENDER_USER_RESOURCE"; \
        blender -b --factory-startup --command extension install --sync --enable vrm; \
        blender -b --factory-startup --python-expr "import addon_utils; m = addon_utils.enable('bl_ext.blender_org.vrm', default_set=False); assert m is not None; print('VRM add-on ok')" | grep "VRM add-on ok"; \
        chown -R jetty:jetty "$BLENDER_USER_RESOURCE"; \
    fi

USER jetty
WORKDIR $JETTY_BASE

RUN java -jar "$JETTY_HOME/start.jar" --create-startd \
    --add-modules=server,http,ee11-deploy,ee11-websocket-jakarta,ee11-webapp,ee11-jsp

COPY --from=builder /war/ROOT.war $JETTY_BASE/webapps/ROOT.war

USER root
RUN mkdir -p /var/joi/data && chown -R jetty:jetty /var/joi
# no COPY --chmod: it needs BuildKit, which the classic builder of older docker/docker-compose v1 lacks
COPY docker-entrypoint.sh /usr/local/bin/joi-entrypoint.sh
RUN chmod 755 /usr/local/bin/joi-entrypoint.sh

# the data folder: the database, the key of the stored secrets, the uploaded files (the compose file mounts it)
ENV JOI_DATA=/var/joi/data

# starts as root to fix the owner of the data folder (a bind mount created by Docker is owned by root), then runs
# Jetty as jetty, see docker-entrypoint.sh
EXPOSE 8080

# healthy when /health answers 200 (the application is up and the database answers); the image has no curl, so bash opens
# the socket. Vaadin takes a while to start, hence the long start period
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && head -n1 <&3 | grep -q "HTTP/1\.. 200"' || exit 1

ENTRYPOINT ["/usr/local/bin/joi-entrypoint.sh"]
CMD ["java", "-jar", "/usr/local/jetty/start.jar"]
