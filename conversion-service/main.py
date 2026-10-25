"""Office-to-PDF service. Deploy only behind authentication and with network restrictions."""
from pathlib import Path
from tempfile import TemporaryDirectory
import os
import subprocess
import hmac
import zipfile
from fastapi import FastAPI, UploadFile, HTTPException, Header
from fastapi.responses import Response

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
MAX_BYTES = 20 * 1024 * 1024
MIMES = {
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/convert")
async def convert(file: UploadFile, x_api_key: str | None = Header(default=None)):
    expected_key = os.environ.get("CONVERSION_API_KEY")
    if not expected_key or not x_api_key or not hmac.compare_digest(x_api_key, expected_key):
        raise HTTPException(401, "Unauthorized")
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in MIMES:
        raise HTTPException(415, "Only DOCX and XLSX are supported")
    with TemporaryDirectory(prefix="docutils_") as directory:
        root = Path(directory)
        incoming = root / ("source" + suffix)
        size = 0
        with incoming.open("wb") as destination:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_BYTES:
                    raise HTTPException(413, "Document exceeds 20 MB limit")
                destination.write(chunk)
        if size == 0:
            raise HTTPException(400, "Empty document")
        # OOXML files are ZIP archives. Reject malformed archives and
        # compression bombs before passing untrusted input to LibreOffice.
        try:
            with zipfile.ZipFile(incoming) as archive:
                members = archive.infolist()
                if len(members) > 5000:
                    raise HTTPException(413, "Too many archive entries")
                if any(member.flag_bits & 0x1 for member in members):
                    raise HTTPException(415, "Encrypted Office files are unsupported")
                total_uncompressed = sum(member.file_size for member in members)
                if total_uncompressed > 200 * 1024 * 1024:
                    raise HTTPException(413, "Office document expands beyond safe limits")
                names = {member.filename for member in members}
                required = "xl/workbook.xml" if suffix == ".xlsx" else "word/document.xml"
                if "[Content_Types].xml" not in names or required not in names:
                    raise HTTPException(415, "Invalid Office document structure")
        except (zipfile.BadZipFile, OSError, ValueError):
            raise HTTPException(415, "Invalid Office ZIP archive")
        output_dir = root / "out"
        output_dir.mkdir()
        profile = root / "profile"
        try:
            result = subprocess.run(
                ["libreoffice", "-env:UserInstallation=file://" + str(profile),
                 "--headless", "--convert-to", "pdf", "--outdir", str(output_dir), str(incoming)],
                capture_output=True, text=True, timeout=90, cwd=str(root),
            )
        except subprocess.TimeoutExpired:
            raise HTTPException(504, "Conversion timed out")
        output = output_dir / "source.pdf"
        if result.returncode != 0 or not output.exists():
            raise HTTPException(422, "Office document could not be converted")
        pdf = output.read_bytes()
        if not pdf.startswith(b"%PDF-"):
            raise HTTPException(500, "Invalid PDF output")
        return Response(content=pdf, media_type="application/pdf",
                        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
