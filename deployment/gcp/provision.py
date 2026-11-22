"""Provision the single-VM development environment with the signed-in gcloud account.

Run from deployment: python3 gcp/provision.py --gcloud /path/to/gcloud
Creates billable resources. Billing must already be linked. Never edits existing disks/secrets.
"""
import argparse
import json
import subprocess

PROJECT = 'project-d786fe4f-a307-4760-93f'
REGION = 'asia-southeast1'
ZONE = REGION + '-b'
VM = 'ipets-develop'
NETWORK = 'ipets-develop'
ACCOUNT = 'ipets-deploy@' + PROJECT + '.iam.gserviceaccount.com'
REPOSITORIES = ('FidDeveloper/ipets-api-service', 'FidDeveloper/ipets-ocr-worker')


def provider_condition(owner_id, repository_ids):
    # Numeric IDs prevent a deleted repository/owner name being claimed by somebody else.
    if not str(owner_id).isdigit() or not repository_ids or not all(str(i).isdigit() for i in repository_ids):
        raise ValueError('GitHub numeric owner and repository IDs required')
    return (f"assertion.repository_owner_id == '{owner_id}' && "
            f"assertion.repository_id in {json.dumps([str(i) for i in repository_ids])} && "
            "assertion.ref == 'refs/heads/develop'")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gcloud', default='gcloud')
    parser.add_argument('--owner-id', required=True)
    parser.add_argument('--repository-ids', nargs=2, required=True)
    args = parser.parse_args()
    condition = provider_condition(args.owner_id, args.repository_ids)

    def run(*parts, capture=False):
        result = subprocess.run([args.gcloud, *parts, '--project=' + PROJECT, '--quiet'],
                                check=True, text=True, capture_output=capture)
        return result.stdout.strip() if capture else None

    def ensure(describe, create):
        result = subprocess.run([args.gcloud, *describe, '--project=' + PROJECT, '--quiet'],
                                text=True, capture_output=True)
        if result.returncode:
            if 'NOT_FOUND' not in result.stderr and 'was not found' not in result.stderr and 'does not exist' not in result.stderr:
                raise RuntimeError(result.stderr)
            run(*create)

    billing = json.loads(run('billing', 'projects', 'describe', PROJECT, '--format=json', capture=True))
    if not billing.get('billingEnabled'):
        raise SystemExit('Link the trial billing account to this project in Google Cloud Console first.')
    number = run('projects', 'describe', PROJECT, '--format=value(projectNumber)', capture=True)
    run('services', 'enable', 'compute.googleapis.com', 'iap.googleapis.com', 'iam.googleapis.com',
        'iamcredentials.googleapis.com', 'sts.googleapis.com', 'run.googleapis.com',
        'artifactregistry.googleapis.com', 'billingbudgets.googleapis.com')
    billing_account = billing['billingAccountName']
    budgets = json.loads(run('billing', 'budgets', 'list', '--billing-account=' + billing_account,
                             '--filter=displayName=ipets-develop-monthly', '--format=json', capture=True))
    if not budgets:
        run('billing', 'budgets', 'create', '--billing-account=' + billing_account,
            '--display-name=ipets-develop-monthly', '--budget-amount=25',
            '--filter-projects=projects/' + number, '--credit-types-treatment=exclude-all-credits',
            '--threshold-rule=percent=0.5', '--threshold-rule=percent=0.9', '--threshold-rule=percent=1.0')
    ensure(('compute', 'networks', 'describe', NETWORK),
           ('compute', 'networks', 'create', NETWORK, '--subnet-mode=custom'))
    for name, cidr in (('ipets-vm', '10.48.0.0/28'), ('ipets-run', '10.48.1.0/26')):
        ensure(('compute', 'networks', 'subnets', 'describe', name, '--region=' + REGION),
               ('compute', 'networks', 'subnets', 'create', name, '--network=' + NETWORK,
                '--range=' + cidr, '--region=' + REGION))
    for name, source, port in (('ipets-iap-ssh', '35.235.240.0/20', '22'),
                               ('ipets-run-api', '10.48.1.0/26', '18080')):
        ensure(('compute', 'firewall-rules', 'describe', name),
               ('compute', 'firewall-rules', 'create', name, '--network=' + NETWORK,
                '--direction=INGRESS', '--action=ALLOW', '--rules=tcp:' + port,
                '--source-ranges=' + source, '--target-tags=ipets-vm'))
    ensure(('iam', 'service-accounts', 'describe', ACCOUNT),
           ('iam', 'service-accounts', 'create', 'ipets-deploy', '--display-name=iPets GitHub deployment'))
    ensure(('compute', 'instances', 'describe', VM, '--zone=' + ZONE),
           ('compute', 'instances', 'create', VM, '--zone=' + ZONE, '--machine-type=e2-highmem-2',
            '--network=' + NETWORK, '--subnet=ipets-vm', '--private-network-ip=10.48.0.2',
            '--tags=ipets-vm', '--image-family=ubuntu-2404-lts-amd64', '--image-project=ubuntu-os-cloud',
            '--boot-disk-size=60GB', '--boot-disk-type=pd-balanced', '--no-service-account', '--no-scopes',
            '--metadata=enable-oslogin=TRUE,block-project-ssh-keys=TRUE'))
    # CI can administer this VM through IAP, but cannot create VMs or change IAM.
    run('compute', 'instances', 'add-iam-policy-binding', VM, '--zone=' + ZONE,
        '--member=serviceAccount:' + ACCOUNT, '--role=roles/compute.osAdminLogin')
    run('projects', 'add-iam-policy-binding', PROJECT, '--member=serviceAccount:' + ACCOUNT,
        '--role=roles/compute.viewer', '--condition=None')
    run('projects', 'add-iam-policy-binding', PROJECT, '--member=serviceAccount:' + ACCOUNT,
        '--role=roles/iap.tunnelResourceAccessor',
        '--condition=expression=destination.port == 22,title=ipets-ssh-only')
    ensure(('iam', 'workload-identity-pools', 'describe', 'github', '--location=global'),
           ('iam', 'workload-identity-pools', 'create', 'github', '--location=global', '--display-name=GitHub Actions'))
    ensure(('iam', 'workload-identity-pools', 'providers', 'describe', 'ipets', '--location=global',
            '--workload-identity-pool=github'),
           ('iam', 'workload-identity-pools', 'providers', 'create-oidc', 'ipets', '--location=global',
            '--workload-identity-pool=github', '--issuer-uri=https://token.actions.githubusercontent.com',
            '--attribute-mapping=google.subject=assertion.sub,attribute.repository=assertion.repository',
            '--attribute-condition=' + condition))
    for repository in REPOSITORIES:
        run('iam', 'service-accounts', 'add-iam-policy-binding', ACCOUNT,
            '--role=roles/iam.workloadIdentityUser',
            f'--member=principalSet://iam.googleapis.com/projects/{number}/locations/global/workloadIdentityPools/github/attribute.repository/{repository}')
    ensure(('artifacts', 'repositories', 'describe', 'ipets-edge', '--location=' + REGION),
           ('artifacts', 'repositories', 'create', 'ipets-edge', '--location=' + REGION,
            '--repository-format=docker', '--description=Small HTTPS proxy image only'))
    variables = {'GCP_PROJECT_ID': PROJECT, 'GCP_ZONE': ZONE, 'GCP_INSTANCE': VM,
                 'GCP_DEPLOY_ACCOUNT': ACCOUNT,
                 'GCP_WIF_PROVIDER': f'projects/{number}/locations/global/workloadIdentityPools/github/providers/ipets'}
    print('Set these GitHub Actions repository variables in both backend repositories; enable only after bootstrap/tests:')
    print(json.dumps(variables, indent=2))
    print('GCP_DEPLOY_ENABLED=true')
    print('VM created. Bootstrap and deploy the HTTPS proxy using gcp/README.md. Stop the VM after testing.')


if __name__ == '__main__':
    main()
