FROM maven:3.9.8-eclipse-temurin-21@sha256:de18e4e51396ad9266f76669779ed6d6b49fbb0f45ed6f39169d87378d548d7d AS jdbc-compat
COPY apache-seatunnel-3.0.0-bin.tar.gz /tmp/seatunnel.tar.gz
COPY connector-jdbc-3.0.0.jar /work/connector-jdbc-3.0.0.jar
COPY jdbc-compat/HospitalOracleDialectFactory.java /work/HospitalOracleDialectFactory.java
RUN mkdir -p /opt/seatunnel /work/classes \
    && tar -xzf /tmp/seatunnel.tar.gz --strip-components=1 -C /opt/seatunnel \
    && javac --release 17 -encoding UTF-8 -proc:none -cp '/work/connector-jdbc-3.0.0.jar:/opt/seatunnel/starter/*:/opt/seatunnel/lib/*' -d /work/classes /work/HospitalOracleDialectFactory.java \
    && cd /work/classes \
    && jar xf /work/connector-jdbc-3.0.0.jar META-INF/services/org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.JdbcDialectFactory \
    && printf '\norg.openmetadata.integration.seatunnel.HospitalOracleDialectFactory\n' >> META-INF/services/org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.JdbcDialectFactory \
    && jar uf /work/connector-jdbc-3.0.0.jar -C /work/classes .

FROM eclipse-temurin:17-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7

ARG SEATUNNEL_VERSION=3.0.0
LABEL org.opencontainers.image.title="Hospital data integration engine" \
      org.opencontainers.image.version="3.0.0" \
      org.opencontainers.image.source="https://github.com/apache/seatunnel"

COPY apache-seatunnel-3.0.0-bin.tar.gz /tmp/seatunnel.tar.gz
RUN mkdir -p /opt/seatunnel /data /opt/seatunnel/logs \
    && tar -xzf /tmp/seatunnel.tar.gz --strip-components=1 -C /opt/seatunnel \
    && rm /tmp/seatunnel.tar.gz \
    && groupadd --gid 1000 seatunnel \
    && useradd --uid 1000 --gid 1000 --home-dir /opt/seatunnel --shell /bin/bash seatunnel

COPY --from=jdbc-compat /work/connector-jdbc-3.0.0.jar /opt/seatunnel/connectors/
COPY connector-cdc-postgres-3.0.0.jar /opt/seatunnel/connectors/
COPY postgresql-42.7.14.jar /opt/seatunnel/lib/
COPY jdbc-drivers/ /opt/seatunnel/lib/
COPY hospital-entrypoint.sh /opt/seatunnel/bin/hospital-entrypoint.sh
RUN chmod 0755 /opt/seatunnel/bin/hospital-entrypoint.sh \
    && sed -i 's/^  java /  exec java /' /opt/seatunnel/bin/seatunnel-cluster.sh \
    && chown -R 1000:1000 /opt/seatunnel /data

ENV SEATUNNEL_HOME=/opt/seatunnel
WORKDIR /opt/seatunnel
USER 1000:1000
EXPOSE 8080 5801
ENTRYPOINT ["/opt/seatunnel/bin/hospital-entrypoint.sh"]
