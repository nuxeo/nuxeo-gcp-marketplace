FROM marketplace.gcr.io/google/debian12 AS build

ARG REGISTRY
ARG TAG

RUN apt-get update \
  && apt-get install -y --no-install-recommends gettext

ADD tester/chart/nuxeo-mp-test /tmp/chart

RUN cd /tmp \
  && tar -czvf /tmp/chart.tar.gz chart/

ADD tester/schema.yaml /tmp
RUN cat /tmp/schema.yaml \
  | env -i "REGISTRY=$REGISTRY" "TAG=$TAG" envsubst \
  > /tmp/schema.yaml.new \
  && mv /tmp/schema.yaml.new /tmp/schema.yaml

FROM gcr.io/cloud-marketplace-tools/k8s/deployer_helm/onbuild

COPY --from=build /tmp/chart.tar.gz /data-test/chart/
COPY --from=build /tmp/schema.yaml /data-test/
