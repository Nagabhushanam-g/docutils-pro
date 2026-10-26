# High-fidelity Office conversion service (prototype)

This is an **optional server-side prototype**, not yet connected to the Android app or deployed. It uses LibreOffice's actual DOCX/XLSX print renderer rather than rebuilding individual cells or paragraphs. It should preserve source page setup substantially better than the current Android basic converter, but **pixel-perfect fidelity is not guaranteed**: Microsoft Office and LibreOffice can paginate differently, and missing fonts can change layout.

## Local run

Build: `docker build -t docutils-converter .`

Run: `docker run --rm -p 8080:8080 -e CONVERSION_API_KEY=replace-with-a-long-random-secret docutils-converter`

Test: `curl -H "X-API-Key: replace-with-a-long-random-secret" -F "file=@example.xlsx" http://localhost:8080/convert --output example.pdf`

## Security and release blockers

- **Do not embed a shared API key in the APK.** Before connecting the app, use authenticated per-user requests through a backend gateway, rate limiting, TLS and explicit consent to upload private documents.
- Restrict network egress, memory, CPU, runtime, concurrent requests, and container privileges. Run with a read-only root filesystem and appropriate temporary writable mounts where possible.
- Scan or sandbox untrusted Office files; LibreOffice processes potentially malicious documents.
- Conversion occurs on a server: documents leave the device. Offer an explicit privacy notice and retention policy before enabling.
- The Android app is **not** using this service yet. No automatic source-faithful conversion is claimed.
- Verify page count, paper orientation, print area, fonts, merged cells, headers, formulas, images, and charts using the supplied acceptance workbook and Word fixtures before release.

## Cloud Run deployment (not automatic)

The manual GitHub Actions workflow `.github/workflows/deploy-conversion-service.yml` is gated by the `conversion-production` GitHub environment. Before triggering it:

1. Choose a Google Cloud project with billing enabled and Cloud Run, Cloud Build, Artifact Registry and Secret Manager APIs enabled.
2. Configure GitHub OIDC Workload Identity Federation and a deployer service account with least-privilege deployment permissions.
3. Set environment secrets `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`, and `GCP_RUNTIME_SERVICE_ACCOUNT`.
4. Set environment variables `GCP_PROJECT_ID` and `GCP_REGION`.
5. Create Secret Manager secret `docutils-conversion-api-key` and grant the Cloud Run runtime identity access to that secret.
6. Review costs and the privacy/security implications before manually running the workflow.

**The deployed Cloud Run service is private and cannot be called directly from the Android APK.** A separate authenticated backend gateway and per-user identity verification are required. Do not disable Cloud Run IAM or copy the API key into the APK as a shortcut. A Cloud Run deployment alone will **not** fix the installed app.

For production, add a secure gateway with user authentication, abuse controls, explicit document-upload consent, and retention controls. Test the user's actual workbook before changing the Android conversion button to use the remote renderer.

## Free, offline desktop conversion (no Google Cloud billing)

For development and layout acceptance testing, install LibreOffice on a Windows, Linux, or macOS computer and run:

```bash
python conversion-service/local_convert.py "Bill_Packing Plant_AUGUST_2026.xlsx"
```

This creates a PDF alongside the original workbook. To choose an output location, supply a second argument ending in `.pdf`. On Windows, LibreOffice's `soffice.exe` must be available on PATH. The utility does not upload documents, use Google Cloud, or incur server charges. It is **a desktop testing tool, not an Android conversion feature**.

Compare the PDF's page count, landscape orientation, print scaling, merged cells, images, and layout against the source workbook. LibreOffice and Microsoft Excel may still render differently. Do not mark Android conversion resolved until an on-device renderer or an authorized backend is integrated and tested.
