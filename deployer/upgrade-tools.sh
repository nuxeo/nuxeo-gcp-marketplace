#!/bin/bash

set -euo pipefail
shopt -s inherit_errexit

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

case $(uname -m) in
  x86_64) arch=amd64 ;;
  aarch64) arch=arm64 ;;
  *)
    echo "Unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

version_pattern='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
prerelease_pattern='(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)'
installed_pattern="${version_pattern%$}(-$prerelease_pattern(\.$prerelease_pattern)*)?$"

validate_version() {
  if [[ ! $1 =~ $version_pattern ]]; then
    echo "Invalid stable version: $1" >&2
    exit 1
  fi
}

download() {
  curl --fail --silent --show-error --location \
    --proto '=https' --proto-redir '=https' \
    --retry 3 --connect-timeout 20 --max-time 600 "$@"
}

installed_version() {
  case $1 in
    kubectl) kubectl version --client -o json | jq -er '.clientVersion.gitVersion' ;;
    helm) helm version --template '{{.Version}}' ;;
  esac
}

latest_helm() {
  local installed=$1 latest page=1 major=${1#v}
  if latest=$(download --header 'Cache-Control: no-cache' \
    "https://get.helm.sh/helm${major%%.*}-latest-version?ts=$(date +%s)"); then
    validate_version "$latest"
    if [[ ${latest%.*} == "${installed%.*}" ]]; then
      printf '%s\n' "$latest"
      return
    fi
  fi

  # The CDN marker may be absent and only covers the newest minor.
  while true; do
    download "https://api.github.com/repos/helm/helm/releases?per_page=100&page=$page" \
      -o "$tmp/releases.json"
    jq -r --arg prefix "${installed%.*}." --arg version_pattern "$version_pattern" '
      if type != "array" then error("Invalid Helm release list") else
        .[] | select(.draft == false and .prerelease == false)
        | .tag_name
        | select(test($version_pattern))
        | select(startswith($prefix))
      end
    ' "$tmp/releases.json" >>"$tmp/helm-tags"
    if [[ $(jq 'length' "$tmp/releases.json") == 0 ]]; then
      break
    fi
    page=$((page + 1))
  done
  sort -V "$tmp/helm-tags" | tail -n 1
}

verify_checksum() {
  local checksum
  checksum=$(awk 'NR == 1 {print $1}' "$tmp/checksum")
  if [[ ! $checksum =~ ^[[:xdigit:]]{64}$ ]]; then
    echo 'Invalid SHA-256 checksum' >&2
    exit 1
  fi
  printf '%s  %s\n' "$checksum" "$1" | sha256sum --check --quiet
}

for tool in kubectl helm; do
  path=$(readlink -f "$(command -v "$tool")")
  old=$(installed_version "$tool")
  if [[ ! $old =~ $installed_pattern ]]; then
    echo "$tool: Invalid installed version: $old" >&2
    exit 1
  fi
  core=${old%%-*}
  minor=${core%.*}
  minor=${minor#v}
  case $tool in
    kubectl) new=$(download "https://dl.k8s.io/release/stable-$minor.txt") ;;
    helm)
      new=$(latest_helm "$core")
      if [[ -z $new && $old != "$core" ]]; then
        printf '%s: already up to date at %s\n' "$tool" "$old"
        continue
      fi
      ;;
  esac
  validate_version "$new"
  if [[ ${new%.*} != "${core%.*}" ]]; then
    echo "$tool release $new does not match installed minor $old" >&2
    exit 1
  fi
  if ((10#${new##*.} < 10#${core##*.})) || [[ $new == "$old" ]]; then
    printf '%s: already up to date at %s\n' "$tool" "$old"
    continue
  fi

  case $tool in
    kubectl)
      url="https://dl.k8s.io/release/$new/bin/linux/$arch/kubectl"
      download "$url" -o "$tmp/kubectl"
      download "$url.sha256" -o "$tmp/checksum"
      verify_checksum "$tmp/kubectl"
      ;;
    helm)
      url="https://get.helm.sh/helm-$new-linux-$arch.tar.gz"
      download "$url" -o "$tmp/helm.tar.gz"
      download "$url.sha256sum" -o "$tmp/checksum"
      verify_checksum "$tmp/helm.tar.gz"
      tar -xzf "$tmp/helm.tar.gz" -C "$tmp" "linux-$arch/helm"
      ;;
  esac
  case $tool in
    kubectl) install -m 0755 "$tmp/kubectl" "$path" ;;
    helm) install -m 0755 "$tmp/linux-$arch/helm" "$path" ;;
  esac
  installed=$(installed_version "$tool")
  if [[ $installed != "$new" ]]; then
    echo "$tool: expected $new after installation, got $installed" >&2
    exit 1
  fi
  printf '%s: upgraded %s -> %s\n' "$tool" "$old" "$new"
done
