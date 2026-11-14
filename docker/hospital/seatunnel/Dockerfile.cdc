FROM harbor.qcrmyy.local/coop/hospital-seatunnel@sha256:477f41ca16167c9fbec07f188235a781aa7bf6ec844de4826c31016b638367c0
ARG SOURCE_REVISION
LABEL org.opencontainers.image.source="https://github.com/Yzero12138/OpenMetadata" \
      org.opencontainers.image.version="3.0.0-cdc-v2" \
      org.opencontainers.image.revision="$SOURCE_REVISION"
COPY --chown=1000:1000 connector-cdc-oracle-3.0.0.jar /opt/seatunnel/connectors/connector-cdc-oracle-3.0.0.jar
COPY --chown=1000:1000 connector-cdc-sqlserver-3.0.0.jar /opt/seatunnel/connectors/connector-cdc-sqlserver-3.0.0.jar
RUN test "$(wc -c < /opt/seatunnel/connectors/connector-cdc-oracle-3.0.0.jar)" -eq 31590975 \
    && echo "a9333a2f03c9207ba7a5a96cc839831000c000343a50473942338c7686f1df4f  /opt/seatunnel/connectors/connector-cdc-oracle-3.0.0.jar" | sha256sum -c - \
    && echo "f33f56cd8ad2625ad91e634b93fe96ef8f24e6a5  /opt/seatunnel/connectors/connector-cdc-oracle-3.0.0.jar" | sha1sum -c - \
    && test "$(wc -c < /opt/seatunnel/connectors/connector-cdc-sqlserver-3.0.0.jar)" -eq 12484442 \
    && echo "85bb3d911e5f387e4c5570868914928fc674b9cee2acaee603505450a4b876de  /opt/seatunnel/connectors/connector-cdc-sqlserver-3.0.0.jar" | sha256sum -c - \
    && echo "0e06e20e9510915b5e90eabc077bcfb6ce777368  /opt/seatunnel/connectors/connector-cdc-sqlserver-3.0.0.jar" | sha1sum -c -
