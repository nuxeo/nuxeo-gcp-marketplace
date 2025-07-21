FROM marketplace.gcr.io/google/debian12 AS build

RUN apt-get update \
  && apt-get install -y --no-install-recommends gettext

ADD tester/chart/nuxeo-mp-test /tmp/chart

RUN cd /tmp \
  && tar -czvf /tmp/chart.tar.gz chart/

ADD tester/schema.yaml /tmp

FROM gcr.io/cloud-marketplace-tools/k8s/deployer_helm/onbuild

ARG BUILD_TAG
ARG SCM_REF
ARG NUXEO_VERSION

LABEL org.nuxeo.build-tag=$BUILD_TAG
LABEL org.nuxeo.scm-ref=$SCM_REF
LABEL org.nuxeo.version=$NUXEO_VERSION

COPY --from=build /tmp/chart.tar.gz /data-test/chart/
COPY --from=build /tmp/schema.yaml /data-test/
