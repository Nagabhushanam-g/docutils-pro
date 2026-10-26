"""Offline converter tests: no network, Google Cloud, or user documents needed."""
from pathlib import Path
import subprocess
import shutil
import pytest
from local_convert import convert


def test_rejects_unsupported_extension(tmp_path):
    source = tmp_path / "file.xls"
    source.write_bytes(b"old excel")
    with pytest.raises(ValueError, match="Only"):
        convert(source, tmp_path / "file.pdf")


def test_rejects_missing_file(tmp_path):
    with pytest.raises(FileNotFoundError):
        convert(tmp_path / "missing.xlsx", tmp_path / "missing.pdf")


def test_converts_real_xlsx_locally(tmp_path):
    pytest.importorskip("pypdf")
    if not (shutil.which("libreoffice") or shutil.which("soffice")):
        pytest.skip("LibreOffice not installed")
    from test_real_conversion import make_xlsx
    from pypdf import PdfReader
    source = tmp_path / "source.xlsx"
    source.write_bytes(make_xlsx())
    output = convert(source, tmp_path / "converted.pdf")
    assert output.exists()
    assert output.read_bytes().startswith(b"%PDF-")
    pages = PdfReader(str(output)).pages
    assert len(pages) == 1
    assert float(pages[0].mediabox.width) > float(pages[0].mediabox.height)


def test_converts_real_docx_locally(tmp_path):
    if not (shutil.which("libreoffice") or shutil.which("soffice")):
        pytest.skip("LibreOffice not installed")
    from test_real_conversion import make_docx
    source = tmp_path / "source.docx"
    source.write_bytes(make_docx())
    output = convert(source, tmp_path / "converted.pdf")
    assert output.read_bytes().startswith(b"%PDF-")
