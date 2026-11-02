# Medi-Scan backend.
#
# Builds for whichever platform it is given; the production target is linux/arm64,
# because EC2 t4g.small is ARM and the fallback host (Oracle Cloud Always Free) is
# Ampere ARM too (docs/plan.md section 11):
#
#   docker buildx build --platform linux/arm64 -t medi-scan-backend backend/
#
# Both base images publish arm64 and amd64 manifests, so no per-arch branching.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /build

# The wrapper and the POM first, on their own. This layer is cached as long as the
# dependency list does not change, so an ordinary source edit does not re-download
# the world - which matters a lot when cross-building under emulation.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/

# Tests are not run here. They need Docker for Testcontainers, which is not
# available inside the build, and running them under arm64 emulation would be
# painfully slow. `./mvnw verify` runs on the host before the image is built.
RUN ./mvnw -B -ntp -DskipTests package \
    && mv target/*.jar app.jar

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime

# Tesseract, as a program this image runs - not as a library it links against
# (phase 4 decision O1, docs/phase-4-lld.md section 13).
#
# Two packages, and deliberately only two:
#   tesseract-ocr     - the `tesseract` command itself, which the backend starts
#                       as a child process with a timeout and an output cap.
#   tesseract-ocr-eng - the English model. Without it `tesseract` installs fine
#                       and then fails on the first page, at runtime, in
#                       production. OcrStartupCheck refuses to start instead.
#
# libtesseract-dev was here for phase 1's planned Tess4J binding and is gone with
# it. A JNA binding loads native OCR code inside the JVM, where a segfault in it
# takes the whole server down; a child process can be killed on timeout and its
# memory returns when it exits. Nothing in the image links against Tesseract any
# more, so the headers and the static library were pure weight.
#
# --no-install-recommends keeps out the X11 and documentation pulls; the apt
# lists are removed in the same layer so they do not persist in the image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --version

# Where the distribution puts the models the `tesseract` command reads.
#
# The command finds these on its own; setting it explicitly means the child
# process inherits an unambiguous value rather than depending on the package's
# compiled-in default, which has moved between Tesseract 4 and 5. Pointing
# OCR_TESSDATA_DIR at a tessdata_best directory overrides it per process, which
# is how the slower, more accurate model would be switched on (LLD 6.2).
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

# Unprivileged, and owns nothing it does not need to. A pipeline that shells out
# to OCR on user-supplied files should not be doing it as root.
RUN groupadd --system --gid 1001 medi \
    && useradd --system --uid 1001 --gid medi --home /app --shell /usr/sbin/nologin medi

WORKDIR /app
COPY --from=build --chown=medi:medi /build/app.jar app.jar

# Uploads land here as temp files and are deleted in a finally block right after
# text extraction. A dedicated directory makes the retention sweep for files
# older than 10 minutes (docs/dataflow.md 9.2) unambiguous.
RUN mkdir -p /app/tmp && chown medi:medi /app/tmp
ENV JAVA_TMPDIR=/app/tmp

USER medi

EXPOSE 8080

# MaxRAMPercentage, not -Xmx: the box has 2 GB and the JVM has to leave room for
# Tesseract's native allocations, which live outside the heap. 60% of 2 GB is
# about 1.2 GB (docs/plan.md section 11).
#
# SerialGC because a single-core-ish 2 GB instance gains nothing from G1's
# background threads and loses memory to them.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Compose and Caddy both need to know when the app is actually serving, not just
# when the process has started. Spring takes seconds to come up and the first
# Neon query after an idle spell is slow, hence the generous start period.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

# Shell form so $JAVA_OPTS is expanded. `exec` keeps Java as PID 1 so it receives
# SIGTERM directly and shuts down gracefully instead of being killed after the
# stop timeout.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -Djava.io.tmpdir=$JAVA_TMPDIR -jar /app/app.jar"]
