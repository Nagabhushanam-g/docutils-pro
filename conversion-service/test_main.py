"""Service contract tests; conversion is mocked to avoid requiring LibreOffice in CI."""
import io
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from fastapi.testclient import TestClient
import main

client = TestClient(main.app)
DOCX = ("document.docx", b"PK\x03\x04fake-docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

def test_health():
    assert client.get("/health").json() == {"status": "ok"}

def test_auth_required(monkeypatch):
    monkeypatch.setenv("CONVERSION_API_KEY", "test-only-key")
    response = client.post("/convert", files={"file": DOCX})
    assert response.status_code == 401

def test_no_key_configured(monkeypatch):
    monkeypatch.delenv("CONVERSION_API_KEY", raising=False)
    response = client.post("/convert", headers={"X-API-Key": "anything"}, files={"file": DOCX})
    assert response.status_code == 401

def test_reject_unsupported_extension(monkeypatch):
    monkeypatch.setenv("CONVERSION_API_KEY", "test-only-key")
    response = client.post("/convert", headers={"X-API-Key": "test-only-key"},
                           files={"file": ("sample.txt", b"hello", "text/plain")})
    assert response.status_code == 415

def test_reject_empty_file(monkeypatch):
    monkeypatch.setenv("CONVERSION_API_KEY", "test-only-key")
    response = client.post("/convert", headers={"X-API-Key": "test-only-key"},
                           files={"file": ("sample.xlsx", b"", "application/octet-stream")})
    assert response.status_code == 400

def test_pdf_response(monkeypatch):
    monkeypatch.setenv("CONVERSION_API_KEY", "test-only-key")
    def fake_run(args, **kwargs):
        out_dir = Path(args[args.index("--outdir") + 1])
        (out_dir / "source.pdf").write_bytes(b"%PDF-1.7\nmock")
        return SimpleNamespace(returncode=0)
    with patch.object(main.subprocess, "run", side_effect=fake_run):
        response = client.post("/convert", headers={"X-API-Key": "test-only-key"},
                               files={"file": DOCX})
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/pdf"
    assert response.content.startswith(b"%PDF-")
    assert response.headers["cache-control"] == "no-store"

def test_timeout(monkeypatch):
    import subprocess
    monkeypatch.setenv("CONVERSION_API_KEY", "test-only-key")
    with patch.object(main.subprocess, "run", side_effect=subprocess.TimeoutExpired("libreoffice", 90)):
        response = client.post("/convert", headers={"X-API-Key": "test-only-key"},
                               files={"file": DOCX})
    assert response.status_code == 504
