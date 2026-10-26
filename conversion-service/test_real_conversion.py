"""Real Office-to-PDF smoke tests run with LibreOffice installed in CI."""
import io
import os
import shutil
import zipfile
from xml.sax.saxutils import escape
from pypdf import PdfReader
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)
KEY = "test-only-key"

def make_xlsx():
    content_types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""
    workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Test" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
    root_rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView workbookViewId="0"/></sheetViews>
<sheetFormatPr defaultRowHeight="15"/>
<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>DocUtils PDF layout smoke test</t></is></c></row></sheetData>
<pageMargins left="0.3" right="0.3" top="0.3" bottom="0.3" header="0.1" footer="0.1"/>
<pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="1"/>
</worksheet>"""
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, data in {
            "[Content_Types].xml": content_types,
            "_rels/.rels": root_rels,
            "xl/workbook.xml": workbook,
            "xl/_rels/workbook.xml.rels": rels,
            "xl/worksheets/sheet1.xml": sheet,
        }.items():
            archive.writestr(name, data)
    return stream.getvalue()

def make_docx():
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
        archive.writestr("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        archive.writestr("word/document.xml", """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>DocUtils Word PDF smoke test</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/></w:sectPr></w:body></w:document>""")
    return stream.getvalue()

def test_real_excel_to_pdf(monkeypatch):
    assert shutil.which("libreoffice"), "LibreOffice is required"
    monkeypatch.setenv("CONVERSION_API_KEY", KEY)
    response = client.post("/convert", headers={"X-API-Key": KEY}, files={
        "file": ("landscape.xlsx", make_xlsx(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    })
    assert response.status_code == 200, response.text
    assert response.content.startswith(b"%PDF-")
    assert len(response.content) > 1000
    pages = PdfReader(io.BytesIO(response.content)).pages
    assert len(pages) == 1, f"Expected one printed source page, got {len(pages)}"
    page = pages[0]
    assert float(page.mediabox.width) > float(page.mediabox.height), "Source landscape orientation was not preserved"

def test_real_word_to_pdf(monkeypatch):
    assert shutil.which("libreoffice"), "LibreOffice is required"
    monkeypatch.setenv("CONVERSION_API_KEY", KEY)
    response = client.post("/convert", headers={"X-API-Key": KEY}, files={
        "file": ("landscape.docx", make_docx(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    })
    assert response.status_code == 200, response.text
    assert response.content.startswith(b"%PDF-")
    assert len(response.content) > 1000
