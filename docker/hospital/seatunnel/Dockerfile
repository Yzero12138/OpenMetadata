FROM eclipse-temurin:17-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7

ARG SEATUNNEL_VERSION=3.0.0
LABEL org.opencontainers.image.title="Hospital synthetic data integration engine" \
      org.opencontainers.image.version="3.0.0" \
      org.opencontainers.image.source="https://github.com/apache/seatunnel"

COPY apache-seatunnel-3.0.0-bin.tar.gz /tmp/seatunnel.tar.gz
RUN mkdir -p /opt/seatunnel /data /opt/seatunnel/logs \
    && tar -xzf /tmp/seatunnel.tar.gz --strip-components=1 -C /opt/seatunnel \
    && rm /tmp/seatunnel.tar.gz \
    && groupadd --gid 1000 seatunnel \
    && useradd --uid 1000 --gid 1000 --home-dir /opt/seatunnel --shell /bin/bash seatunnel

COPY connector-jdbc-3.0.0.jar connector-cdc-postgres-3.0.0.jar /opt/seatunnel/connectors/
COPY postgresql-42.7.14.jar /opt/seatunnel/lib/
COPY hospital-entrypoint.sh /opt/seatunnel/bin/hospital-entrypoint.sh
RUN chmod 0755 /opt/seatunnel/bin/hospital-entrypoint.sh \
    && sed -i 's/^  java /  exec java /' /opt/seatunnel/bin/seatunnel-cluster.sh \
    && chown -R 1000:1000 /opt/seatunnel /data

ENV SEATUNNEL_HOME=/opt/seatunnel
WORKDIR /opt/seatunnel
USER 1000:1000
EXPOSE 8080 5801
ENTRYPOINT ["/opt/seatunnel/bin/hospital-entrypoint.sh"]
