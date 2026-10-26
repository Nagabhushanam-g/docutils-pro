"""Free local Office-to-PDF conversion using installed LibreOffice.

Usage (Windows): py -3 tools/convert_office_local.py "C:\\Docs\\book.xlsx"
Usage (other): python tools/convert_office_local.py ./book.xlsx --output ./book.pdf

This tool deliberately does NOT change the source print settings. Conversion
may differ from Microsoft Office where fonts or rendering engines differ.
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SUPPORTED = {".xlsx", ".xls", ".docx", ".doc"}


def find_libreoffice():
    for name in ("soffice", "libreoffice"):
        found = shutil.which(name)
        if found:
            return found
    if sys.platform == "win32":
        for root in (
            Path("C:/Program Files/LibreOffice/program/soffice.exe"),
            Path("C:/Program Files (x86)/LibreOffice/program/soffice.exe"),
        ):
            if root.is_file():
                return str(root)
    raise RuntimeError(
        "LibreOffice not found. Install the free desktop version, then retry."
    )


def convert(source: Path, output: Path) -> Path:
    source = source.expanduser().resolve(strict=True)
    output = output.expanduser().resolve()
    if not source.is_file() or source.suffix.lower() not in SUPPORTED:
        raise ValueError("Input must be an existing .xlsx, .xls, .docx or .doc file")
    if output.suffix.lower() != ".pdf":
        raise ValueError("Output must end in .pdf")
    if source == output:
        raise ValueError("Source and destination cannot be the same")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="docutils_local_") as folder:
        root = Path(folder)
        # Work on a copy so the source file is never modified.
        staged = root / ("source" + source.suffix.lower())
        shutil.copyfile(source, staged)
        rendered_dir = root / "rendered"
        rendered_dir.mkdir()
        profile = (root / "lo-profile").as_uri()
        command = [
            find_libreoffice(),
            "-env:UserInstallation=" + profile,
            "--headless",
            "--convert-to", "pdf",
            "--outdir", str(rendered_dir),
            str(staged),
        ]
        try:
            result = subprocess.run(
                command, capture_output=True, text=True, timeout=180,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError("Conversion timed out after 180 seconds") from exc
        rendered = rendered_dir / "source.pdf"
        if result.returncode != 0 or not rendered.is_file():
            raise RuntimeError(
                "LibreOffice could not convert the file. "
                + (result.stderr or result.stdout).strip()[:600]
            )
        with rendered.open("rb") as stream:
            if stream.read(5) != b"%PDF-":
                raise RuntimeError("Converter returned an invalid PDF")
        # Copy only after successful rendering, avoiding a partial destination.
        temp_output = output.with_name(output.name + ".part")
        try:
            shutil.copyfile(rendered, temp_output)
            temp_output.replace(output)
        finally:
            temp_output.unlink(missing_ok=True)
    return output


def main():
    parser = argparse.ArgumentParser(
        description="Convert Office files to PDF locally without a paid server."
    )
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, help="PDF path (defaults beside input)")
    args = parser.parse_args()
    destination = args.output or args.source.with_suffix(".pdf")
    try:
        result = convert(args.source, destination)
    except (OSError, ValueError, RuntimeError) as exc:
        parser.exit(1, f"Conversion failed: {exc}\n")
    print(f"PDF saved: {result}")


if __name__ == "__main__":
    main()
