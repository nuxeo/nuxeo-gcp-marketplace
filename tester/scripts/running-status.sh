#!/bin/sh

set -eu

echo "Running command: curl $1/runningstatus -v --retry $2 --retry-connrefused"
runningStatus=$(curl "$1/runningstatus" -v --retry "$2" --retry-connrefused)
echo "Running status: $runningStatus"

runtimeStatus=$(printf '%s\n' "$runningStatus" | jq -r '.runtimeStatus')
repositoryStatus=$(printf '%s\n' "$runningStatus" | jq -r '.repositoryStatus')
streamStatus=$(printf '%s\n' "$runningStatus" | jq -r '.streamStatus')

if [ "$runtimeStatus" = 'ok' ] && [ "$repositoryStatus" = 'ok' ] && [ "$streamStatus" = 'ok' ]; then
  exit 0
else
  exit 1
fi
