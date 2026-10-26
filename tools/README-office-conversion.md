# Free local Office-to-PDF conversion

This is a **Windows/macOS/Linux desktop testing utility**, not an Android APK feature. It uses LibreOffice's renderer without modifying source print settings.

## Windows setup

1. Install LibreOffice Desktop (free) from its official website.
2. Download this repository or just `tools/convert_office_local.py`.
3. Open PowerShell in the repository folder.
4. Run:

```powershell
py -3 tools/convert_office_local.py "C:\\Documents\\Bill_Packing Plant_AUGUST_2026.xlsx"
```

The PDF will be saved next to the source file. You can choose another output path:

```powershell
py -3 tools/convert_office_local.py "C:\\Documents\\example.docx" --output "C:\\Documents\\example-converted.pdf"
```

Python 3 and LibreOffice must be installed. No third-party Python packages are required.

## Acceptance testing

- Open the source workbook in Excel or LibreOffice and check Print Preview.
- Compare each PDF page's size, orientation, margins, print areas, row/column fitting, merged cells, charts, and text.
- For the reported workbook, inspect sheets `KCR_1` and `KCR_2` and confirm that no artificial four-column pagination occurs.
- If layout differs, check that the same fonts are installed on the conversion machine. LibreOffice is not guaranteed to paginate identically to Microsoft Excel or Word.

## Limits

This utility runs on the computer where LibreOffice is installed. It does **not** make the Android app convert files offline or eliminate the need for an accessible conversion engine when converting from a phone. Do not expose the prototype conversion API publicly without strong authentication and privacy controls.
