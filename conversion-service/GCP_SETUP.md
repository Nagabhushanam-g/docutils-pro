# DocUtils Pro — Google Cloud setup

Project: `docutils-pro`  
Region: `asia-south1`  
GitHub: `Nagabhushanam-g/docutils-pro`  
Cloud Run service: `docutils-conversion`

> This is a preparation checklist, **not a record of deployment**. Execute commands from an authenticated Google Cloud Shell session with sufficient project permissions. Review each IAM grant before executing. Cloud Run/Cloud Build may incur charges.

## 1. Project and APIs

```bash
gcloud config set project docutils-pro
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com
PROJECT_NUMBER=$(gcloud projects describe docutils-pro --format='value(projectNumber)')
echo "Project number: $PROJECT_NUMBER"
```

## 2. Service accounts and a secret

```bash
gcloud iam service-accounts create docutils-gh-deployer --display-name="DocUtils GitHub deployer"
gcloud iam service-accounts create docutils-converter-runtime --display-name="DocUtils conversion runtime"

# Generate a long random secret, never put it in GitHub, Android, or chat.
openssl rand -hex 32 | gcloud secrets create docutils-conversion-api-key --replication-policy=automatic --data-file=-

gcloud secrets add-iam-policy-binding docutils-conversion-api-key \
  --member="serviceAccount:docutils-converter-runtime@docutils-pro.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

## 3. Grant deployer permissions

```bash
DEPLOYER="serviceAccount:docutils-gh-deployer@docutils-pro.iam.gserviceaccount.com"
for ROLE in roles/run.admin roles/cloudbuild.builds.editor roles/artifactregistry.admin roles/serviceusage.serviceUsageConsumer; do
  gcloud projects add-iam-policy-binding docutils-pro --member="$DEPLOYER" --role="$ROLE"
done

gcloud iam service-accounts add-iam-policy-binding \
  docutils-converter-runtime@docutils-pro.iam.gserviceaccount.com \
  --member="$DEPLOYER" --role="roles/iam.serviceAccountUser"
```

Cloud Build also requires permission to build and push the container image. Google Cloud's default Cloud Build identity depends on the project configuration. If deployment reports a permission error, inspect the build identity in Cloud Build and grant only the specific missing permissions rather than granting Editor broadly.

## 4. GitHub Workload Identity Federation

```bash
gcloud iam workload-identity-pools create docutils-github \
  --location=global --display-name="DocUtils GitHub"

gcloud iam workload-identity-pools providers create-oidc docutils-github-provider \
  --location=global --workload-identity-pool=docutils-github \
  --display-name="DocUtils GitHub Actions" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
  --attribute-condition="assertion.repository=='Nagabhushanam-g/docutils-pro' && assertion.ref=='refs/heads/main'"

gcloud iam service-accounts add-iam-policy-binding \
  docutils-gh-deployer@docutils-pro.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/docutils-github/attribute.repository/Nagabhushanam-g/docutils-pro"
```

## 5. GitHub environment configuration

In the repository, open **Settings → Environments**, create `conversion-production`, restrict deployments to `main`, and optionally require approval.

Set environment **secrets**:

| Name | Value |
|---|---|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/docutils-github/providers/docutils-github-provider` (substitute actual project number) |
| `GCP_DEPLOY_SERVICE_ACCOUNT` | `docutils-gh-deployer@docutils-pro.iam.gserviceaccount.com` |
| `GCP_RUNTIME_SERVICE_ACCOUNT` | `docutils-converter-runtime@docutils-pro.iam.gserviceaccount.com` |

Set environment **variables**:

| Name | Value |
|---|---|
| `GCP_PROJECT_ID` | `docutils-pro` |
| `GCP_REGION` | `asia-south1` |

Do **not** add the secret API key to GitHub; the deployment references Google Secret Manager.

## 6. Deploy (manual, after reviewing costs)

Open **Actions → Deploy Office PDF Conversion Service → Run workflow** on `main`.

The workflow deploys with `--no-allow-unauthenticated` and restricted ingress. It is **not** a public endpoint and **cannot be called directly from the Android app**.

## 7. Remaining production blockers

- A secure authenticated gateway for Android requests (the mobile app must not contain the service API key).
- Explicit consent before uploading private Word/Excel documents, privacy/retention controls, and abuse/rate limiting.
- Real-file acceptance test for `Bill_Packing Plant_AUGUST_2026.xlsx` and a representative Word file.
- Android integration and successful end-to-end PDF test.

**Do not enable public unauthenticated Cloud Run access or embed `CONVERSION_API_KEY` in an APK.**
