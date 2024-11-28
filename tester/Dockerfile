FROM gcr.io/cloud-marketplace-tools/testrunner

RUN apt-get update && apt-get install -y --no-install-recommends \
  curl \
  jq \
  wget \
  && rm -rf /var/lib/apt/lists/*

COPY scripts /scripts
COPY tests /tests

WORKDIR /
ENTRYPOINT ["/scripts/tester.sh"]
