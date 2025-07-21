FROM gcr.io/cloud-marketplace-tools/testrunner

ARG BUILD_TAG
ARG SCM_REF
ARG NUXEO_VERSION

LABEL org.nuxeo.build-tag=$BUILD_TAG
LABEL org.nuxeo.scm-ref=$SCM_REF
LABEL org.nuxeo.version=$NUXEO_VERSION

RUN apt-get update && apt-get install -y --no-install-recommends \
  curl \
  jq \
  wget \
  && rm -rf /var/lib/apt/lists/*

COPY scripts /scripts
COPY tests /tests

WORKDIR /
ENTRYPOINT ["/scripts/tester.sh"]
