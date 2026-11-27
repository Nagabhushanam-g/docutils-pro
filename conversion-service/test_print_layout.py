"""Regression tests for source-print-layout fidelity in the LibreOffice engine.

The two-sheet fixture has exactly one landscape page per source worksheet.
A converter must not fragment those two printed sheets into extra pages.
"""
import io
import os
import zipfile
from pathlib import Path

from pypdf import PdfReader
from local_convert import convert
from test_real_conversion import make_xlsx


def two_page_landscape_workbook() -> bytes:
    """Extend the one-sheet valid OOXML fixture into two landscape worksheets."""
    original = make_xlsx()
    source = {}
    with zipfile.ZipFile(io.BytesIO(original)) as archive:
        for name in archive.namelist():
            source[name] = archive.read(name)
    workbook = source["xl/workbook.xml"].decode()
    workbook = workbook.replace(
        "</sheets>",
        '<sheet name="Second page" sheetId="2" r:id="rId2"/></sheets>',
    )
    source["xl/workbook.xml"] = workbook.encode()
    rels = source["xl/_rels/workbook.xml.rels"].decode()
    rels = rels.replace(
        "</Relationships>",
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>',
    )
    source["xl/_rels/workbook.xml.rels"] = rels.encode()
    types = source["[Content_Types].xml"].decode()
    types = types.replace(
        "</Types>",
        '<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
    )
    source["[Content_Types].xml"] = types.encode()
    source["xl/worksheets/sheet2.xml"] = source["xl/worksheets/sheet1.xml"].replace(
        b"DocUtils PDF layout smoke test", b"DocUtils second printed page"
    )
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as dest:
        for name, data in source.items():
            dest.writestr(name, data)
    return stream.getvalue()


def test_two_landscape_excel_pages_are_preserved(tmp_path: Path):
    source = tmp_path / "two-landscape-pages.xlsx"
    source.write_bytes(two_page_landscape_workbook())
    result = convert(source, tmp_path / "two-landscape-pages.pdf")
    reader = PdfReader(str(result))
    assert len(reader.pages) == 2, (
        f"Two source print pages must produce two PDF pages; got {len(reader.pages)}"
    )
    for page in reader.pages:
        assert float(page.mediabox.width) > float(page.mediabox.height), (
            "Landscape source page was changed to portrait"
        )
    extracted = "\n".join(page.extract_text() or "" for page in reader.pages)
    assert "DocUtils PDF layout smoke test" in extracted
    assert "DocUtils second printed page" in extracted
