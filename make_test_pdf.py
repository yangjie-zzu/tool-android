# 构造最小测试 PDF: 3 页 + 层级书签树(顶级/子级), 用于验证 PdfOutlineParser
import sys

def make_pdf(path):
    objs = {}
    objs[1] = b"<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R >>"
    objs[2] = b"<< /Type /Pages /Kids [3 0 R 12 0 R 13 0 R] /Count 3 >>"
    objs[3] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 8 0 R >> >> >>"
    def page_stream(text, num):
        content = f"BT /F1 24 Tf 72 700 Td ({text}) Tj ET".encode()
        return (f"<< /Length {len(content)} >>\nstream\n" ).encode() + content + b"\nendstream"
    objs[4] = page_stream("Page 1 - Chapter One", 4)
    objs[5] = b"<< /Type /Outlines /First 6 0 R /Last 6 0 R /Count 2 >>"
    objs[6] = b"<< /Title (Chapter One) /Parent 5 0 R /Dest [3 0 R /XYZ null null null] /Next 14 0 R >>"
    objs[7] = b"<< /Title (Section 1.1) /Parent 6 0 R /Dest [12 0 R /Fit] >>"
    objs[8] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    # page2/3 as independent page objects (12, 13)
    objs[12] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 15 0 R /Resources << /Font << /F1 8 0 R >> >> >>"
    objs[13] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 16 0 R /Resources << /Font << /F1 8 0 R >> >> >>"
    objs[14] = b"<< /Title (Chapter Two) /Parent 5 0 R /Prev 6 0 R /Dest [13 0 R /XYZ null null null] >>"
    objs[15] = page_stream("Page 2 - Section 1.1", 15)
    objs[16] = page_stream("Page 3 - Chapter Two", 16)

    out = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for num in sorted(objs):
        offsets[num] = len(out)
        out += f"{num} 0 obj\n".encode() + objs[num] + b"\nendobj\n"
    xref = len(out)
    maxn = max(objs) + 1
    out += b"xref\n"
    out += f"0 {maxn}\n".encode()
    out += b"0000000000 65535 f \n"
    for i in range(1, maxn):
        if i in offsets:
            out += f"{offsets[i]:010d} 00000 n \n".encode()
        else:
            out += b"0000000000 65535 f \n"
    out += b"trailer\n<< /Size " + str(maxn).encode() + b" /Root 1 0 R >>\nstartxref\n"
    out += str(xref).encode() + b"\n%%EOF\n"
    open(path, "wb").write(bytes(out))

make_pdf(sys.argv[1] if len(sys.argv) > 1 else "test_outline.pdf")
print("ok")
