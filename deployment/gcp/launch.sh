#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
gcloud=${GCLOUD:-gcloud}
project=project-d786fe4f-a307-4760-93f
region=asia-southeast1
zone=asia-southeast1-b
vm=ipets-develop
python3 gcp/provision.py --gcloud "$gcloud" --owner-id 110385422 --repository-ids 1467048738 1467049586
status=$("$gcloud" compute instances describe "$vm" --project="$project" --zone="$zone" --format='value(status)')
if [[ $status != RUNNING ]]; then
  "$gcloud" compute instances start "$vm" --project="$project" --zone="$zone" --quiet
fi
for attempt in {1..30}; do
  if "$gcloud" compute ssh "$vm" --project="$project" --zone="$zone" --tunnel-through-iap --quiet --command=true; then break; fi
  [[ $attempt != 30 ]] || exit 1
  sleep 5
done
bundle=$(mktemp /tmp/ipets-deployment.XXXXXX)
registry_dir=$(mktemp -d /tmp/ipets-registry.XXXXXX)
trap 'rm -f "$bundle"; rm -rf "$registry_dir"' EXIT
tar -czf "$bundle" --exclude=.env --exclude='.env.*' --exclude=images.env --exclude=images.env.previous --exclude=__pycache__ .
"$gcloud" compute scp "$bundle" "$vm:/tmp/ipets-bootstrap.tgz" --project="$project" --zone="$zone" --tunnel-through-iap --quiet
"$gcloud" compute ssh "$vm" --project="$project" --zone="$zone" --tunnel-through-iap --quiet \
  --command='sudo install -d -m 700 /opt/ipets/deployment && sudo tar -xzf /tmp/ipets-bootstrap.tgz -C /opt/ipets/deployment && sudo bash /opt/ipets/deployment/gcp/bootstrap.sh /tmp/ipets-bootstrap.tgz'
image="$region-docker.pkg.dev/$project/ipets-edge/proxy:$(git rev-parse --short HEAD)"
docker buildx build --platform linux/amd64 --load -t "$image" gcp/edge
# A temporary registry config prevents Google tokens being retained in Docker's normal config.
docker_endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
"$gcloud" auth print-access-token | DOCKER_HOST="$docker_endpoint" docker --config "$registry_dir" login -u oauth2accesstoken --password-stdin "https://$region-docker.pkg.dev"
DOCKER_HOST="$docker_endpoint" docker --config "$registry_dir" push "$image"
edge_account="ipets-edge@$project.iam.gserviceaccount.com"
if ! "$gcloud" iam service-accounts describe "$edge_account" --project="$project" --quiet >/dev/null 2>&1; then
  "$gcloud" iam service-accounts create ipets-edge --project="$project" --quiet
fi
"$gcloud" run deploy ipets-edge --project="$project" --region="$region" --image="$image" \
  --service-account="$edge_account" --network=ipets-develop --subnet=ipets-run \
  --vpc-egress=private-ranges-only --allow-unauthenticated --ingress=all \
  --port=8080 --cpu=1 --memory=128Mi --min=0 --max=1 --concurrency=40 \
  --cpu-throttling --timeout=90 --quiet
url=$("$gcloud" run services describe ipets-edge --project="$project" --region="$region" --format='value(status.url)')
IPETS_E2E_BASE="$url" python3 gcp/test_deployed.py
printf '\nDevelopment API: %s\nSet mobile repository variable EXPO_PUBLIC_API_URL to this address.\n' "$url"
