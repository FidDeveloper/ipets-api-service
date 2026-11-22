#!/usr/bin/env bash
set -euo pipefail
cd /opt/ipets/deployment
exec 9>/var/lock/ipets-deploy.lock
flock -w 900 9
service=${1:?Service required}
image=${2:-}
case "$service" in
  auth-service) key=IPETS_AUTH_IMAGE; repository=ipets-api-service/auth ;;
  pet-service) key=IPETS_PET_IMAGE; repository=ipets-api-service/pet ;;
  document-service) key=IPETS_DOCUMENT_IMAGE; repository=ipets-api-service/document ;;
  notification-service) key=IPETS_NOTIFICATION_IMAGE; repository=ipets-api-service/notification ;;
  ocr-worker) key=IPETS_OCR_IMAGE; repository=ipets-ocr-worker ;;
  stack) key='' ;;
  *) echo 'Unknown service'; exit 2 ;;
esac
if [[ $service != stack ]]; then
  [[ $image =~ ^ghcr.io/fiddeveloper/$repository:[a-f0-9]{40}$ ]] || { echo 'Expected a matching immutable Git SHA image'; exit 2; }
fi
if [[ -n ${3:-} ]]; then
  # The bundle contains deployment code only; secrets and data never travel in CI.
  tar -xzf "$3" -C .
fi
cp images.env images.env.previous
if [[ -n $key ]]; then
  sed -i "/^${key}=/d" images.env
  printf '%s=%s\n' "$key" "$image" >> images.env
fi
compose=(docker compose -p ipets-develop --env-file .env --env-file images.env -f compose.yaml -f gcp/compose.yaml)
rollback() {
  trap - ERR
  cp images.env.previous images.env
  "${compose[@]}" up -d --no-build || true
  echo 'Deployment failed; restored previous image selections. Database migrations require manual review.'
  exit 1
}
trap rollback ERR
if [[ $service == stack ]]; then
  # Refresh all successful develop builds, then pin exactly what was pulled.
  : > images.env
  "${compose[@]}" pull
  for pair in AUTH:auth PET:pet DOCUMENT:document NOTIFICATION:notification OCR:ocr; do
    name=${pair%:*}; short=${pair#*:}
    repository="ipets-api-service/$short"
    [[ $short != ocr ]] || repository=ipets-ocr-worker
    digest=$(docker image inspect "ghcr.io/fiddeveloper/$repository:develop" --format '{{index .RepoDigests 0}}')
    printf 'IPETS_%s_IMAGE=%s\n' "$name" "$digest" >> images.env
  done
  "${compose[@]}" up -d --no-build
else
  "${compose[@]}" pull "$service"
  "${compose[@]}" up -d --no-build --no-deps "$service"
fi
python3 smoke.py
python3 gcp/test_deployed.py
date -u '+%Y-%m-%dT%H:%M:%SZ deployment passed'
