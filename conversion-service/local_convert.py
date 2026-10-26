#!/usr/bin/env python3
"""Convert a DOCX or XLSX locally using LibreOffice, without cloud upload or billing."""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def convert(source: Path, destination: Path) -> Path:
    source = source.expanduser().resolve()
    destination = destination.expanduser().resolve()
    if source.suffix.lower() not in {".xlsx", ".docx"}:
        raise ValueError("Only .xlsx and .docx are supported")
    if not source.is_file():
        raise FileNotFoundError(source)
    if not shutil.which("libreoffice") and not shutil.which("soffice"):
        raise RuntimeError("LibreOffice is not installed or is not on PATH")
    executable = shutil.which("libreoffice") or shutil.which("soffice")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="docutils_local_") as tmp:
        root = Path(tmp)
        incoming = root / ("source" + source.suffix.lower())
        shutil.copy2(source, incoming)
        output = root / "output"
        output.mkdir()
        profile = root / "profile"
        result = subprocess.run(
            [executable, "-env:UserInstallation=" + profile.as_uri(), "--headless",
             "--convert-to", "pdf", "--outdir", str(output), str(incoming)],
            capture_output=True, text=True, timeout=120,
        )
        generated = output / "source.pdf"
        if result.returncode or not generated.exists():
            raise RuntimeError("LibreOffice conversion failed: " + (result.stderr or result.stdout))
        if generated.open("rb").read(5) != b"%PDF-":
            raise RuntimeError("LibreOffice produced an invalid PDF")
        shutil.copy2(generated, destination)
    return destination


def main():
    parser = argparse.ArgumentParser(description="Offline Excel/Word to PDF using LibreOffice")
    parser.add_argument("source", type=Path, help="Input .xlsx or .docx file")
    parser.add_argument("output", nargs="?", type=Path, help="Output PDF path (default: next to source)")
    args = parser.parse_args()
    output = args.output or args.source.with_suffix(".pdf")
    try:
        result = convert(args.source, output)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
        print("Conversion failed:", exc, file=sys.stderr)
        return 1
    print("Saved PDF:", result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
