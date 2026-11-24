#!/bin/bash

set -euo pipefail

: "${APP_INSTANCE_NAME:?APP_INSTANCE_NAME must be set}"

exec /scripts/running-status.sh "http://${APP_INSTANCE_NAME}-nuxeo/nuxeo" 8
