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
