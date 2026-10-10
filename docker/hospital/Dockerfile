# Build context is prepared by build-image.ps1 and contains no credentials.
FROM maven:3.9.8-eclipse-temurin-21@sha256:de18e4e51396ad9266f76669779ed6d6b49fbb0f45ed6f39169d87378d548d7d AS distribution
COPY openmetadata-2.0.4.tar.gz /tmp/openmetadata.tar.gz
RUN mkdir -p /opt/openmetadata \
    && tar xzf /tmp/openmetadata.tar.gz -C /opt/openmetadata --strip-components=1
COPY openmetadata-service-2.0.4.jar /opt/openmetadata/libs/openmetadata-service-2.0.4.jar
COPY openmetadata-ui-2.0.4.jar /opt/openmetadata/libs/openmetadata-ui-2.0.4.jar
RUN test -f /opt/openmetadata/libs/mysql-connector-j-9.3.0.jar \
    && rm /opt/openmetadata/libs/mysql-connector-j-9.3.0.jar
COPY jdbc-drivers/ /opt/openmetadata/libs/

FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
LABEL org.opencontainers.image.source="https://github.com/Yzero12138/OpenMetadata"
LABEL org.opencontainers.image.version="2.0.4-integrate-v6"
COPY --from=distribution --chown=1000:1000 /opt/openmetadata /opt/openmetadata
WORKDIR /opt/openmetadata
USER 1000:1000
EXPOSE 8585 8586
ENTRYPOINT ["/bin/bash", "./bin/openmetadata-server-start.sh"]
CMD ["conf/openmetadata.yaml"]
