# Stages: builder (the platform from the Marginalia submodule, the protocol and the server, built into the WAR with the
# production frontend bundle), extensions-build and extensions (the extensions that come with Joi, as JARs) and runtime (the image, the default target, so it must stay the last stage). The node is
# not part of the image: it runs on the user's machines. Build from the repository root, with the submodule checked out
# (git clone --recurse-submodules, or git submodule update --init).
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

# Cache dependencies. The POMs of the reactor come first (the root POM lists joi-sherpa and joi-node, so their POMs must be there too,
# their sources are not: neither is built here). The platform's parent (marginalia/pom.xml) and the root POM are installed, then the platform's
# dependencies are fetched; what only the server needs (the build plugins, Vaadin's) is downloaded by the build below
COPY pom.xml ./pom.xml
COPY marginalia/pom.xml ./marginalia/pom.xml
COPY marginalia/marginalia/platform/pom.xml ./marginalia/marginalia/platform/pom.xml
COPY joi-protocol/pom.xml ./joi-protocol/pom.xml
COPY joi-sherpa/pom.xml ./joi-sherpa/pom.xml
COPY joi-server/pom.xml ./joi-server/pom.xml
COPY joi-node/pom.xml ./joi-node/pom.xml
COPY joi-extensions/pom.xml ./joi-extensions/pom.xml
COPY joi-extensions/anthropic/pom.xml ./joi-extensions/anthropic/pom.xml
COPY joi-extensions/google-genai/pom.xml ./joi-extensions/google-genai/pom.xml
COPY joi-extensions/grok/pom.xml ./joi-extensions/grok/pom.xml
RUN mvn install -N -B -f pom.xml \
    && mvn install -N -B -f marginalia/pom.xml \
    && mvn dependency:go-offline -B -f marginalia/marginalia/platform/pom.xml

# The sources, then the WAR (the platform and the protocol are built first, -am). Tests are skipped; run them outside.
# install, not just package: the platform's JAR and the parent POMs go into the local Maven repository, where the extensions
# find them (the extensions stage below starts from this stage); the WAR's copy there is deleted again. The frontend tooling
# (node_modules, Node.js in ~/.vaadin, the npm cache) is deleted in the same step, so it never gets into a layer.
COPY marginalia/marginalia/platform/src ./marginalia/marginalia/platform/src
COPY joi-protocol/src ./joi-protocol/src
COPY joi-server/src ./joi-server/src
# the commit the build is made from (git.properties, shown by Administration -> Update); the builder stage only, never in the image
COPY .git ./.git
RUN mvn install -B -DskipTests -Pproduction -pl joi-server -am \
    && mkdir /war && cp joi-server/target/joi-server-*.war /war/ROOT.war \
    && find "$HOME/.m2/repository/io/github/enerccio/joi-server" -name '*.war' -delete \
    && rm -rf joi-server/node_modules "$HOME/.vaadin" "$HOME/.npm" "$HOME/.cache"

# The extensions that come with Joi (the model providers Anthropic Claude, Google Gemini and xAI Grok), built against the
# platform the builder installed. Not part of the server's image: they are loaded from the extensions folder of the data
# folder, which is a volume (see joi-extensions/docker-compose.yml). BuildKit skips these stages for the runtime image.
FROM builder AS extensions-build

COPY joi-extensions/anthropic/src ./joi-extensions/anthropic/src
COPY joi-extensions/google-genai/src ./joi-extensions/google-genai/src
COPY joi-extensions/grok/src ./joi-extensions/grok/src
RUN mkdir /jars \
    && mvn package -B -DskipTests -pl joi-extensions/anthropic,joi-extensions/google-genai,joi-extensions/grok \
    && cp joi-extensions/*/target/*.jar /jars/

# The extension JARs; the command copies them into the mounted folder /output, replacing the ones from an earlier build
# (a bind mount exists only when a container runs, so they can't be copied out during the build)
FROM alpine:3 AS extensions

COPY --from=extensions-build /jars /jars

CMD ["sh", "-c", "mkdir -p /output && rm -f /output/*.jar && cp /jars/*.jar /output/ && ls -l /output"]

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
# Blender (BLENDER_USER_RESOURCES, the name Blender reads: with any other name it uses the home folder of whoever runs it, and the
# server, which runs as jetty, would not find what root installed), which the jetty user owns; the check runs as jetty, as the server does. Build without Blender (about 1 GB smaller) with
# --build-arg WITH_BLENDER=false
ARG WITH_BLENDER=true
ARG BLENDER_VERSION=5.2.1
ENV BLENDER_USER_RESOURCES=/opt/blender-user
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
        mkdir -p "$BLENDER_USER_RESOURCES"; \
        blender --online-mode -b --factory-startup --command extension install --sync --enable vrm; \
        chown -R jetty:jetty "$BLENDER_USER_RESOURCES"; \
        setpriv --reuid=jetty --regid=jetty --init-groups blender -b --factory-startup --python-expr "import os, bpy, addon_utils; bpy.ops.wm.read_factory_settings(use_empty=True); m = addon_utils.enable('bl_ext.blender_org.vrm', default_set=True); assert os.path.realpath(bpy.utils.resource_path('USER')).startswith(os.path.realpath(os.environ['BLENDER_USER_RESOURCES'])), bpy.utils.resource_path('USER'); assert m is not None and hasattr(bpy.types.Armature, 'vrm_addon_extension'); print('VRM add-on ok')" | grep "VRM add-on ok"; \
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
