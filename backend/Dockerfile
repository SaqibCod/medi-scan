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

# OCR native libraries.
#
# Installed now, in phase 1, even though OCR is phase 4: adding them later would
# mean rebuilding this layer and re-verifying the arm64 build at the point where
# the OCR work itself needs attention. Tess4J binds to the system Tesseract via
# JNA, so all three are needed - tesseract-ocr for the binary, -eng for the
# English language data (without it OCR fails at runtime, not at build), and
# libtesseract-dev for the shared library JNA loads.
#
# --no-install-recommends keeps out the X11 and documentation pulls; the apt
# lists are removed in the same layer so they do not persist in the image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        libtesseract-dev \
    && rm -rf /var/lib/apt/lists/*

# Tess4J looks here for language data.
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
